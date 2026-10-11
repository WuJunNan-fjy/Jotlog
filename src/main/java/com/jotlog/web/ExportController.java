package com.jotlog.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotlog.repository.AttachmentRepository;
import com.jotlog.repository.EntryRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全量数据导出。
 *
 * 两种格式：
 *   json     —— 机器可读，搬家 / 重新导入用。条目 + 附件元数据两个平铺数组
 *   markdown —— 人可读，按时间正序合成单个 .md，适合归档翻看
 *
 * 刻意不导出附件二进制：自用库量级下一次请求把所有图片打进 zip 会拖垮内存和连接。
 * 附件文件仍可随时通过 /api/attachments/{sha}/raw 取，JSON 里保留了全部 sha 与文件名。
 *
 * 在 JWT 之后：这是全站数据最完整的出口。
 */
@RestController
@RequestMapping("/api")
public class ExportController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EntryRepository entries;
    private final AttachmentRepository attachments;
    private final ObjectMapper mapper;

    public ExportController(EntryRepository entries,
                            AttachmentRepository attachments,
                            ObjectMapper mapper) {
        this.entries = entries;
        this.attachments = attachments;
        this.mapper = mapper;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "json") String format) throws Exception {
        List<EntryRepository.ExportRow> rows = entries.exportAll();
        String stamp = LocalDateTime.now().format(STAMP);

        if ("md".equalsIgnoreCase(format) || "markdown".equalsIgnoreCase(format)) {
            String md = renderMarkdown(rows);
            return file("jotlog-export-" + stamp + ".md",
                    MediaType.parseMediaType("text/markdown; charset=UTF-8"),
                    md.getBytes(StandardCharsets.UTF_8));
        }
        if (!"json".equalsIgnoreCase(format)) {
            // 不走异常体系：这是导出接口的参数错误，直接 400 即可
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"format 只支持 json 或 markdown\"}".getBytes(StandardCharsets.UTF_8));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("app", "jotlog");
        payload.put("exportedAt", LocalDateTime.now().format(HUMAN));
        payload.put("count", rows.size());
        payload.put("entries", rows);
        payload.put("attachments", attachments.allForExport());
        byte[] body = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        return file("jotlog-export-" + stamp + ".json",
                MediaType.APPLICATION_JSON, body);
    }

    private String renderMarkdown(List<EntryRepository.ExportRow> rows) {
        Map<Long, List<AttachmentRepository.ExportAttachment>> byEntry = attachments.allForExport()
                .stream()
                .collect(Collectors.groupingBy(AttachmentRepository.ExportAttachment::entryId));

        StringBuilder sb = new StringBuilder();
        sb.append("# Jotlog 导出\n\n");
        sb.append("导出时间：").append(LocalDateTime.now().format(HUMAN)).append("\n");
        sb.append("共 ").append(rows.size()).append(" 条记录\n\n---\n\n");

        for (EntryRepository.ExportRow e : rows) {
            String heading = e.title() != null && !e.title().isBlank()
                    ? e.title().trim()
                    : firstLine(e.rawInput());
            sb.append("## ").append(escapeHeading(heading)).append("\n\n");

            sb.append("- 时间：").append(nullDash(e.createdAt()));
            sb.append("　类型：").append(nullDash(e.entryType()));
            sb.append("　来源：").append(nullDash(e.source())).append('\n');
            if (e.url() != null && !e.url().isBlank()) {
                sb.append("- 链接：").append(e.url().trim()).append('\n');
            }
            List<String> flags = new java.util.ArrayList<>(2);
            if (e.starred()) flags.add("星标");
            if (e.archived()) flags.add("已归档");
            if (!flags.isEmpty()) {
                sb.append("- 状态：").append(String.join("、", flags)).append('\n');
            }
            sb.append('\n');

            sb.append(e.rawInput() == null ? "" : e.rawInput().strip()).append("\n\n");

            if (e.aiSummary() != null && !e.aiSummary().isBlank()) {
                sb.append("**AI 摘要：**\n\n").append(e.aiSummary().strip()).append("\n\n");
            }
            if (e.note() != null && !e.note().isBlank()) {
                sb.append("**备注：**\n\n").append(e.note().strip()).append("\n\n");
            }

            List<AttachmentRepository.ExportAttachment> atts = byEntry.get(e.id());
            if (atts != null && !atts.isEmpty()) {
                sb.append("附件：\n");
                for (AttachmentRepository.ExportAttachment a : atts) {
                    sb.append("- ").append(a.filename())
                            .append("（").append(a.mime() == null ? "未知类型" : a.mime())
                            .append("，sha256: ").append(a.sha256()).append("）\n");
                }
                sb.append('\n');
            }
            sb.append("---\n\n");
        }
        return sb.toString();
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "（无内容）";
        }
        String line = text.strip().split("\\R", 2)[0];
        return line.length() <= 60 ? line : line.substring(0, 60) + "…";
    }

    /** 标题里出现换行会破坏 markdown 结构；井号开头也得转义。 */
    private static String escapeHeading(String s) {
        String oneLine = s.replaceAll("\\R", " ").trim();
        if (oneLine.startsWith("#")) {
            return "\\" + oneLine;
        }
        return oneLine;
    }

    private static String nullDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static ResponseEntity<byte[]> file(String filename, MediaType mime, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mime);
        headers.setContentLength(body.length);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        // 私人数据，任何一层都不许缓存
        headers.setCacheControl("private, no-store");
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }
}
