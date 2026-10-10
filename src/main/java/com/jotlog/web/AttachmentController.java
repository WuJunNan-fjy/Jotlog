package com.jotlog.web;

import com.jotlog.repository.AttachmentRepository;
import com.jotlog.storage.StorageNotFoundException;
import com.jotlog.storage.StorageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 附件接口。
 *
 * 为什么用 sha 而不是附件 id 做 raw 端点的键：附件按内容 sha256 去重，
 * 同一个文件在多条记录里共享同一份存储对象，sha 天然就是稳定地址，
 * 前端列表里只需要带上这一个字符串就能显示缩略图。
 * sha 是 256 位随机散列，本身就是"不可猜的凭证"——但接口仍然在 JWT 之后，
 * 不依赖不可猜测性。
 */
@RestController
@RequestMapping("/api")
public class AttachmentController {

    private final AttachmentRepository attachments;
    private final StorageService storage;

    public AttachmentController(AttachmentRepository attachments, StorageService storage) {
        this.attachments = attachments;
        this.storage = storage;
    }

    /** 某条记录的全部附件元数据。详情面板用它渲染图片墙和文件列表。 */
    @GetMapping("/entries/{id}/attachments")
    public Object list(@PathVariable long id) {
        return attachments.metaByEntry(id);
    }

    /**
     * 附件内容。默认尽量 inline（浏览器能直接预览的都直接看），
     * ?download=1 或浏览器自身无法预览的类型则走下载。
     *
     * 安全是这里的重心，三道闸：
     *   1. sha 必须是 64 位十六进制 —— 参数直接拼路径的接口都是穿越漏洞
     *   2. 落盘路径必须在存储根目录内 —— 由 LocalStorageService.open 防御，
     *      防 DB 被污染后读任意文件
     *   3. inline 白名单 —— text/html 和 image/svg+xml 可以内嵌脚本，
     *      一律降级成 octet-stream 强制下载，绝不在站点域内渲染
     */
    @GetMapping("/attachments/{sha}/raw")
    public ResponseEntity<Object> raw(@PathVariable String sha,
                                      @RequestParam(defaultValue="false") boolean download) {
        if (!sha.matches("[0-9a-fA-F]{64}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "非法的附件标识"));
        }

        AttachmentRepository.Stored stored = attachments.findBySha(sha.toLowerCase()).orElse(null);
        if (stored == null) {
            return ResponseEntity.notFound().build();
        }

        String mime = safeMime(stored.mime());
        boolean inline = !download && isInlineSafe(mime);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(inline ? mime : "application/octet-stream"));
        if (stored.sizeBytes() > 0) {
            headers.setContentLength(stored.sizeBytes());
        }
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition(stored.filename(), inline));
        // 附件是私人数据，任何一层缓存都不许存
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");

        try {
            InputStream content = storage.open(
                    new StorageService.StoredObject(stored.storagePath(), stored.sizeBytes()));
            Resource body = new InputStreamResource(content);
            return new ResponseEntity<>(body, headers, HttpStatus.OK);
        } catch (StorageNotFoundException e) {
            // 存储对象没了：DB 记录还在，但内容已经不存在（手动清理过目录 / OSS 对象被删）
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "附件文件不存在"));
        }
    }

    /** 没记 mime 或记了奇怪的值，按二进制流处理，浏览器会下载而不是猜。 */
    private static String safeMime(String mime) {
        if (mime == null || mime.isBlank() || !mime.matches("[\\w.+-]+/[\\w.+-]+(\\s*;.*)?")) {
            return "application/octet-stream";
        }
        return mime.split(";")[0].trim().toLowerCase();
    }

    /**
     * 能安全地在站点域内直接渲染的类型。
     * svg 虽然是 image/*，但能携带 script —— 攻击者只要让你导入一条带恶意 svg
     * 的记录，脚本就在你的域里跑了。宁可下载，不做例外。
     */
    private static boolean isInlineSafe(String mime) {
        if (mime.startsWith("image/") && !"image/svg+xml".equals(mime)) return true;
        if (mime.startsWith("audio/") || mime.startsWith("video/")) return true;
        return "application/pdf".equals(mime) || "text/plain".equals(mime);
    }

    /**
     * filename* 用 RFC 5987 编码（中文文件名），fallback 的 filename 用 ASCII。
     * 只保留 ASCII 可打印字符，其余替换成下划线。
     */
    private static String disposition(String filename, boolean inline) {
        String type = inline ? "inline" : "attachment";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String ascii = filename.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "_");
        return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
