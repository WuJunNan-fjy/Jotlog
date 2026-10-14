package com.jotlog.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotlog.config.AiProperties;
import com.jotlog.repository.CategoryRepository.CategoryRow;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DeepSeek 实现（OpenAI 兼容协议）。
 *
 * 分类防发散靠两件事：
 *   1. prompt 里给出全部分类白名单，要求只回 id；
 *   2. AiResponseParser 校验 id 必须存在，不存在直接判失败。
 * 标签防发散：prompt 带上高频常用标签要求优先复用，再经 TagNormalizer 归一化。
 */
@Component
@ConditionalOnBean(ChatModel.class)
public class DeepSeekAiOrganizer implements AiOrganizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatModel chat;
    private final AiProperties props;

    public DeepSeekAiOrganizer(ChatModel chat, AiProperties props) {
        this.chat = chat;
        this.props = props;
    }

    @Override
    public AiResult organize(String rawInput, List<LinkMaterial> materials,
                             List<CategoryRow> cats, List<String> popularTags) {
        String prompt = """
                你在整理用户的私人随手记。只输出 JSON，不要输出任何解释或 markdown 代码块。

                规则：
                1. 只依据"材料"，不补充材料之外的信息；材料不足就在 summary 里写"信息不足"，不要猜测。
                2. categoryId 必须从"分类清单"中选一个 id；都不合适时填 null，
                   并在 suggestedCategory 给一个不超过 6 个字的新分类名。
                3. tags 优先复用"常用标签"里的原词；确需新词时给 3-5 个，每个尽量不超过 6 个字。
                4. summary 不超过 200 字，中文，平实，不要营销腔。
                   视频类没有正文时只能描述页面标题与用户转发文案，写成"分享文案称…"，禁止描述画面。
                5. title 不超过 40 字。

                输出格式：{"categoryId":数字或null,"suggestedCategory":字符串或null,
                "title":"","summary":"","tags":["",""]}
                """
                + "\n分类清单：\n" + cats.stream().map(c -> c.id() + ":" + c.name())
                        .collect(Collectors.joining(", "))
                + "\n常用标签：" + String.join("、", popularTags)
                + "\n用户原话：\n" + clip(rawInput, 2000)
                + "\n材料：\n" + materialsText(materials);

        String answer = chat.chat(prompt);
        return AiResponseParser.parse(answer, cats);
    }

    @Override
    public List<String> suggestCategories(List<String> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        String prompt = """
                下面是用户随手记的一些原文抽样。请归纳出 6-8 个互斥、好懂的中文分类名
                （每个不超过 6 个字，覆盖工具/项目/文章/视频/想法/资讯这类常见场景）。
                只输出 JSON：{"categories":["名1","名2"]}

                原文抽样：
                """ + samples.stream().map(s -> "- " + clip(s, 200)).collect(Collectors.joining("\n"));
        try {
            String answer = chat.chat(prompt);
            String body = answer.strip()
                    .replaceAll("(?s)^```(?:json)?", "")
                    .replaceAll("```$", "").strip();
            Map<?, ?> node = JSON.readValue(body, Map.class);
            if (node.get("categories") instanceof List<?> list) {
                return list.stream()
                        .filter(x -> x instanceof String)
                        .map(x -> ((String) x).strip())
                        .filter(x -> !x.isEmpty())
                        .limit(8)
                        .toList();
            }
        } catch (Exception e) {
            // 返回空，调用方用内置种子兜底，不把引导做成硬失败
        }
        return List.of();
    }

    @Override
    public String modelName() {
        return props.getModel();
    }

    private static String materialsText(List<LinkMaterial> ms) {
        if (ms == null || ms.isEmpty()) {
            return "（本条没有链接）";
        }
        StringBuilder sb = new StringBuilder();
        for (LinkMaterial m : ms) {
            sb.append("- [").append(m.kind()).append("] ").append(m.url());
            if (m.title() != null) {
                sb.append("\n  标题：").append(m.title());
            }
            if (m.excerpt() != null) {
                sb.append("\n  摘录：").append(m.excerpt());
            }
            if ("failed".equals(m.status())) {
                sb.append("\n  （抓取失败，不要猜测该链接内容）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
