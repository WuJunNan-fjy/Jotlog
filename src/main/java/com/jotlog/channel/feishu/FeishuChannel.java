package com.jotlog.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotlog.channel.ChannelAdapter;
import com.jotlog.config.FeishuProperties;
import com.jotlog.core.Entry;
import com.jotlog.core.Entry.ReplyTarget;
import com.jotlog.core.EntryType;
import com.lark.oapi.Client;
import com.lark.oapi.core.Config;
import com.lark.oapi.core.Transport;
import com.lark.oapi.core.enums.AppType;
import com.lark.oapi.core.enums.BaseUrlEnum;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 飞书长连接通道。
 *
 * 用官方 lark.oapi.ws.Client，内置重连、心跳和鉴权。
 * 不需要公网 IP，不需要回调地址，不需要自己处理加解密。
 *
 * 硬约束：事件回调必须在 3 秒内返回且不抛异常，否则飞书会重推。
 * 所以 onEvent 里只做"丢进队列"，真正的入库和回执交给工作线程。
 *
 * 类名与方法签名对照 SDK 2.8.5 实际 jar 确认，非文档推测。
 */
@Component
public class FeishuChannel implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    private final FeishuProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private Client client;

    public FeishuChannel(FeishuProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "feishu";
    }

    @Override
    public void start(Handler handler) {
        if (!props.isEnabled()) {
            log.info("飞书通道未启用（jotlog.channel.feishu.enabled=false），跳过启动");
            return;
        }
        if (props.getAppId().isBlank() || props.getAppSecret().isBlank()) {
            log.warn("飞书通道已启用但app-id / app-secret 缺失，跳过启动");
            return;
        }

        // API 客户端：用于回执和推送
        //
        // ⚠️ 必须用 Client.newBuilder()，不能用 new Client() + setConfig()。
        //    new Client() 只造了个空壳，im/contact 等所有 service 字段都是 null，
        //    调用 client.im() 会直接 NPE（踩过：回执时炸
        //    "Cannot invoke ImService.message() because Client.im() is null"）。
        //    Builder 才是真正把所有 service 注入进去的入口。
        this.client = Client.newBuilder(props.getAppId(), props.getAppSecret())
                .appType(AppType.SELF_BUILT)
                .openBaseUrl(BaseUrlEnum.FeiShu)
                .build();

        // 启动自检：宁可启动时就喊出来，也不要等到用户发消息才 NPE。
        if (this.client.im() == null) {
            log.error("飞书 API 客户端初始化异常：client.im() 为 null，回执功能不可用");
        }

        // 长连接客户端：Config 与 ws.Client 是两套，互不通用
        EventDispatcher.Builder builder = EventDispatcher.newBuilder("", "");
        builder.onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
            @Override
            public void handle(P2MessageReceiveV1 event) {
                try {
                    onEvent(event, handler);
                } catch (Exception e) {
                    // 绝不能向外抛。抛出去飞书会重推，用户会收到重复消息。
                    log.error("处理飞书事件失败", e);
                }
            }
        });
        EventDispatcher dispatcher = builder.build();

        com.lark.oapi.ws.Client wsClient = new com.lark.oapi.ws.Client.Builder(
                        props.getAppId(), props.getAppSecret())
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .build();

        // start() 内部阻塞直到连接关闭，放独立守护线程
        Thread t = new Thread(() -> {
            log.info("飞书长连接启动中，appId={}", props.getAppId());
            wsClient.start();
        }, "feishu-ws");
        t.setDaemon(true);
        t.start();
    }

    private void onEvent(P2MessageReceiveV1 event, Handler handler) {
        var msg = event.getEvent().getMessage();
        if (msg == null) {
            return;
        }

        String messageId = msg.getMessageId();
        String chatId = msg.getChatId();
        String chatType = msg.getChatType();

        // 群聊里只响应 @ 机器人的消息，否则群里所有聊天都会被吞进来
        if (props.isMentionOnly() && !"p2p".equals(chatType)) {
            var mentions = msg.getMentions();
            if (mentions == null || mentions.length == 0) {
                return;
            }
        }

        String msgType = msg.getMessageType();
        String content = msg.getContent();
        String text = extractText(msgType, content);

        // 附件消息：同步路径只"报名"，不做任何网络调用。
        // 占位文本此时首次写入（raw_input 从不改写），带上文件名让时间线可读；
        // 真正的下载在回执之后由 Pipeline 异步补挂 —— 铁律：入库 → 回执 → 异步增强。
        List<Entry.Attachment> attachments = List.of();
        EntryType declaredType = EntryType.UNKNOWN;

        if ((text == null || text.isBlank())
                && ("image".equals(msgType) || "file".equals(msgType))) {
            ResourceRef ref = extractResource(msgType, content);
            if (ref == null) {
                log.warn("附件消息解析失败 msgType={} messageId={} content={}",
                        msgType, messageId, abbreviate(content));
                return;
            }
            declaredType = "image".equals(msgType) ? EntryType.IMAGE : EntryType.FILE;
            text = ref.placeholder();
            attachments = List.of(new Entry.Attachment(
                    ref.filename(), null, null, ref.fileKey()));
        } else if (text == null || text.isBlank()) {
            log.debug("暂不处理的类型 msgType={} messageId={}", msgType, messageId);
            return;
        }

        String senderOpenId = null;
        var sender = event.getEvent().getSender();
        if (sender != null && sender.getSenderId() != null) {
            senderOpenId = sender.getSenderId().getOpenId();
        }

        ReplyTarget target = new ReplyTarget(chatId, chatType, messageId, senderOpenId);
        Entry entry = new Entry(
                text,
                declaredType,
                null,
                target,
                name(),
                messageId,
                attachments,
                Instant.now());

        log.info("收到飞书消息 messageId={} chatType={} type={} text={}",
                messageId, chatType, msgType, abbreviate(text));
        handler.onEntry(entry);
    }

    private record ResourceRef(String fileKey, String filename, String placeholder) {
    }

    /**
     * 从附件消息的 content JSON 里取资源标识。
     *
     * 文件消息：{"file_key":"...","file_name":"提示词.txt"}
     * 图片消息：{"image_key":"..."}（无文件名，下载响应里可能给）
     */
    private ResourceRef extractResource(String msgType, String content) {
        try {
            var node = mapper.readTree(content);
            if ("image".equals(msgType)) {
                String key = node.path("image_key").asText(null);
                return key == null ? null : new ResourceRef(key, null, "[图片]");
            }
            String key = node.path("file_key").asText(null);
            if (key == null) {
                return null;
            }
            String name = node.path("file_name").asText("未命名文件");
            return new ResourceRef(key, name, "[文件] " + name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 飞书错误码：资源超过 100MB，整包下载被拒。
     * 官方规定 file 类型必须改走 Range 分片（单片 ≤32MB），image 类型不支持分片。
     */
    public static final int CODE_RESOURCE_TOO_LARGE = 234037;

    /** Range 分片单片窗口：飞书要求单片不超过 32MB，取 16MB 留余量。 */
    private static final long RANGE_CHUNK = 16L * 1024 * 1024;

    private static final String RESOURCE_PATH =
            "/open-apis/im/v1/messages/:message_id/resources/:file_key";

    /**
     * 下载一条消息的资源（文件或图片）。
     *
     * 只在异步线程调用 —— 这里有网络往返，出现在事件回调里就违反 3 秒铁律。
     * type 参数：file 消息传 "file"，image 消息传 "image"，飞书按它区分取资源的方式。
     *
     * 整包读进内存，只适合 100MB 以下的资源（飞书单请求硬上限）；
     * 超限会抛 {@link ResourceTooLargeException}，调用方改走 {@link #downloadRange}。
     *
     * @return 字节 + 实际文件名；失败返回 null（条目已入库，附件缺失不致命）
     * @throws ResourceTooLargeException 资源达到 100MB 必须分片下载
     */
    public Downloaded download(String messageId, String fileKey, String type) {
        if (client == null || client.im() == null) {
            log.error("资源下载不可用：API 客户端未初始化（见启动自检日志）");
            return null;
        }
        try {
            var resp = client.im().messageResource().get(
                    new GetMessageResourceReq.Builder()
                            .messageId(messageId)
                            .fileKey(fileKey)
                            .type(type)
                            .build());

            if (!resp.success() || resp.getData() == null) {
                if (resp.getCode() == CODE_RESOURCE_TOO_LARGE) {
                    // 不降级成 null：null 会被当成普通失败静默跳过，
                    // 这里必须让调用方知道"文件太大，该换分片路径"
                    throw new ResourceTooLargeException(resp.getCode(), resp.getMsg());
                }
                log.error("资源下载失败 code={} msg={} fileKey={}",
                        resp.getCode(), resp.getMsg(), fileKey);
                return null;
            }
            return new Downloaded(resp.getFileName(), resp.getData().toByteArray());
        } catch (ResourceTooLargeException e) {
            throw e;
        } catch (Exception e) {
            log.error("资源下载异常 messageId={} fileKey={}", messageId, fileKey, e);
            return null;
        }
    }

    /**
     * Range 分片下载大资源（仅 file 类型；image 飞书不支持分片）。
     *
     * 为什么绕开 SDK 的 messageResource().get()：那个封装把 HTTP 200 当成功、
     * 其他状态码一律按 JSON 错误体反序列化，而分片成功返回的是 206，
     * 会被误判成"非法响应"抛异常。这里直接用底层 Transport.send —— token 注入、
     * 签名等仍由 SDK 完成，我们只自己发 Range 头、收 206、合并字节流。
     *
     * 内容直接写进调用方给的 sink（通常是临时文件 + DigestOutputStream），
     * 500MB 文件的堆内占用峰值只有一个分片，-Xmx512m 也不会 OOM。
     *
     * @param sink 调用方负责关闭
     * @return 飞书在 Content-Disposition 里给的文件名，可能为 null
     */
    public String downloadRange(String messageId, String fileKey, String type, OutputStream sink)
            throws IOException {
        if (client == null || client.im() == null) {
            throw new IOException("资源下载不可用：API 客户端未初始化（见启动自检日志）");
        }

        Config config;
        try {
            config = client.accessToken().getConfig();
        } catch (Exception e) {
            throw new IOException("飞书客户端配置不可用", e);
        }
        if (config == null) {
            throw new IOException("飞书客户端配置不可用");
        }

        var req = new GetMessageResourceReq.Builder()
                .messageId(messageId)
                .fileKey(fileKey)
                .type(type)
                .build();

        long received = 0;
        long total = -1;
        String fileName = null;

        try {
            while (total < 0 || received < total) {
                // 首片时 total 未知，窗口右边界按固定片长算；拿到 Content-Range 后会被总长收敛
                long end = Math.min(received + RANGE_CHUNK, total > 0 ? total : Long.MAX_VALUE) - 1;
                RangePart part = fetchRangeWithRetry(config, req, received, end);

                if (part.status == 200) {
                    // 服务端没按 Range 切（小文件或网关行为）：这一包就是完整内容
                    sink.write(part.body);
                    received += part.body.length;
                    total = received;
                    if (fileName == null) {
                        fileName = part.fileName;
                    }
                    break;
                }
                // 206：part.body 非空由 fetchRangeWithRetry 保证
                sink.write(part.body);
                received += part.body.length;
                if (fileName == null) {
                    fileName = part.fileName;
                }
                if (total < 0) {
                    total = parseTotalFromContentRange(part.contentRange);
                    if (total <= 0) {
                        throw new IOException("无法从 Content-Range 解析资源总大小");
                    }
                }
                sink.flush();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(
                    "分片下载异常 messageId=" + messageId + " fileKey=" + fileKey, e);
        }

        log.info("大资源分片下载完成 messageId={} fileKey={} size={}",
                messageId, fileKey, received);
        return fileName;
    }

    /** 单片最多尝试次数（含首次）。家用宽带上行/飞书网关偶发抖动常见，3 次基本能跨过。 */
    private static final int RANGE_MAX_ATTEMPTS = 3;

    /**
     * 获取单个 Range 分片，带有限重试。
     *
     * 关键安全点：重试只发生在"本片字节尚未写入 sink"之前 ——
     * RawResponse 把整片读成 byte[] 返回，只有状态码/内容校验通过才回到调用方落盘，
     * 因此任何失败重试都不会让下载文件里混入半截分片。
     *
     * 不重试的情况：4xx 业务错误（鉴权 234002、不可见 234040 等），
     * 重试只会得到同样结果，包成 {@link NonRetryableRangeException} 立即终止整次下载。
     * 可重试：网络异常、429、5xx、206 空体；退避 0.5s/1s。
     */
    private RangePart fetchRangeWithRetry(Config config, GetMessageResourceReq req,
                                          long start, long end) throws IOException {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Range", List.of("bytes=" + start + "-" + end));

        Exception lastError = null;
        for (int attempt = 1; attempt <= RANGE_MAX_ATTEMPTS; attempt++) {
            try {
                RequestOptions options = RequestOptions.newBuilder()
                        .supportDownLoad()
                        .headers(headers)
                        .build();
                RawResponse resp = Transport.send(
                        config, options, "GET", RESOURCE_PATH,
                        Set.of(AccessTokenType.Tenant), req);

                int status = resp.getStatusCode();
                byte[] body = resp.getBody() == null ? new byte[0] : resp.getBody();

                if (status == 200 || status == 206) {
                    if (body.length == 0) {
                        // 空片没有终止条件保护会死循环，按可重试的瞬时异常处理
                        throw new IOException("分片下载返回空内容 start=" + start + " status=" + status);
                    }
                    return new RangePart(status, body,
                            resp.getFileName(), header(resp, "Content-Range"));
                }

                IOException apiError = rangeApiError(status, body);
                if (status == 429 || status >= 500) {
                    lastError = apiError; // 限流/服务端错误：退避后重试
                } else {
                    // 4xx 业务错误：重试无意义，立即终止
                    throw new NonRetryableRangeException(apiError.getMessage());
                }
            } catch (NonRetryableRangeException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
            }

            if (attempt < RANGE_MAX_ATTEMPTS) {
                log.warn("分片下载失败，准备重试 start={} attempt={}/3 cause={}",
                        start, attempt, lastError == null ? "未知错误" : lastError.getMessage());
                try {
                    // 指数退避：0.5s、1s
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("分片下载等待重试时被中断", ie);
                }
            }
        }

        throw new IOException("分片下载重试 " + RANGE_MAX_ATTEMPTS
                + " 次仍失败 start=" + start, lastError);
    }

    /** 把飞书错误响应（JSON：code/msg）解析成带诊断信息的异常。 */
    private IOException rangeApiError(int status, byte[] body) {
        String respBody = new String(body, StandardCharsets.UTF_8);
        int code = -1;
        String msg = "";
        try {
            var node = mapper.readTree(respBody);
            code = node.path("code").asInt(-1);
            msg = node.path("msg").asText("");
        } catch (Exception ignored) {
            // body 不是 JSON 也无所谓，下面把原始片段带进异常
        }
        return new IOException("分片下载被拒绝 http=" + status
                + " code=" + code + " msg=" + msg
                + " body=" + respBody.substring(0, Math.min(respBody.length(), 200)));
    }

    /** 单片结果。status 只可能是 200（整包返回）或 206（分片）。 */
    private record RangePart(int status, byte[] body, String fileName, String contentRange) {
    }

    /** 业务级 4xx 错误，重试无意义，用于直接穿透重试循环。 */
    private static class NonRetryableRangeException extends IOException {
        NonRetryableRangeException(String message) {
            super(message);
        }
    }

    /** 响应头大小写不敏感取值（OkHttp 多 map 里头名可能是小写）。 */
    private static String header(RawResponse resp, String name) {
        for (Map.Entry<String, List<String>> e : resp.getHeaders().entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    /** Content-Range: bytes 0-16777215/524288000 → 524288000；无法解析返回 -1。 */
    private static long parseTotalFromContentRange(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= contentRange.length()) {
            return -1;
        }
        String tail = contentRange.substring(slash + 1).trim();
        if ("*".equals(tail)) {
            return -1;
        }
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 整包下载撞上飞书 100MB 上限（错误码 234037）。
     * 必须用 unchecked：SDK 调用点的签名由父接口决定，且这是"换路径"信号而非普通失败。
     */
    public static class ResourceTooLargeException extends RuntimeException {
        private final int code;

        public ResourceTooLargeException(int code, String msg) {
            super("飞书资源超过 100MB 整包下载上限，须分片下载 code=" + code + " msg=" + msg);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public record Downloaded(String filename, byte[] bytes) {
    }

    /**
     * 飞书消息内容是 JSON 字符串，形如 {"text":"内容"}。
     */
    private String extractText(String msgType, String content) {
        if (content == null || !"text".equals(msgType)) {
            return null;
        }
        try {
            var node = mapper.readTree(content);
            return node.has("text") ? node.get("text").asText() : null;
        } catch (Exception e) {
            log.warn("解析消息内容失败: {}", abbreviate(content));
            return null;
        }
    }

    @Override
    public void reply(ReplyTarget target, String text) {
        if (client == null || target == null || !target.valid()) {
            return;
        }
        try {
            ReplyMessageReq req = new ReplyMessageReq.Builder()
                    .messageId(target.messageId())
                    .replyMessageReqBody(new ReplyMessageReqBody.Builder()
                            .content(textContent(text))
                            .msgType("text")
                            .build())
                    .build();

            var resp = client.im().message().reply(req);
            if (!resp.success()) {
                log.error("回执失败 code={} msg={}", resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.error("回执异常", e);
        }
    }

    /**
     * AI 润色完成后主动推送。用 chat_id 发回原会话。
     */
    @Override
    public void push(ReplyTarget target, String text) {
        if (client == null || target == null || target.chatId() == null) {
            return;
        }
        try {
            CreateMessageReq req = new CreateMessageReq.Builder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(new CreateMessageReqBody.Builder()
                            .receiveId(target.chatId())
                            .msgType("text")
                            .content(textContent(text))
                            .build())
                    .build();

            var resp = client.im().message().create(req);
            if (!resp.success()) {
                log.error("推送失败 code={} msg={}", resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.error("推送异常", e);
        }
    }

    private String textContent(String text) {
        try {
            return mapper.writeValueAsString(Map.of("text", text));
        } catch (Exception e) {
            return "{\"text\":\"\"}";
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
