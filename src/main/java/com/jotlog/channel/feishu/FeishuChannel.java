package com.jotlog.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotlog.channel.ChannelAdapter;
import com.jotlog.config.FeishuProperties;
import com.jotlog.core.Entry;
import com.jotlog.core.Entry.ReplyTarget;
import com.jotlog.core.EntryType;
import com.lark.oapi.Client;
import com.lark.oapi.core.enums.AppType;
import com.lark.oapi.core.enums.BaseUrlEnum;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
     * 下载一条消息的资源（文件或图片）。
     *
     * 只在异步线程调用 —— 这里有网络往返，出现在事件回调里就违反 3 秒铁律。
     * type 参数：file 消息传 "file"，image 消息传 "image"，飞书按它区分取资源的方式。
     *
     * @return 字节 + 实际文件名；失败返回 null（条目已入库，附件缺失不致命）
     */
    public Downloaded download(String messageId, String fileKey, String type) {
        if (client == null || client.im() == null) {
            log.error("资源下载不可用：API 客户端未初始化（见启动自检日志）");
            return null;
        }
        try {
            var resp = client.im().messageResource().get(
                    new com.lark.oapi.service.im.v1.model.GetMessageResourceReq.Builder()
                            .messageId(messageId)
                            .fileKey(fileKey)
                            .type(type)
                            .build());

            if (!resp.success() || resp.getData() == null) {
                log.error("资源下载失败 code={} msg={} fileKey={}",
                        resp.getCode(), resp.getMsg(), fileKey);
                return null;
            }
            return new Downloaded(resp.getFileName(), resp.getData().toByteArray());
        } catch (Exception e) {
            log.error("资源下载异常 messageId={} fileKey={}", messageId, fileKey, e);
            return null;
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
