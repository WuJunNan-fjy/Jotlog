package com.jotlog.enrich;

import com.jotlog.repository.CategoryRepository.CategoryRow;

import java.util.List;

/**
 * AI 能力的唯一抽象。换模型厂商只需要换一个实现，worker / 接口层不动。
 */
public interface AiOrganizer {

    /**
     * 分类 + 总结 + 标签。
     * 素材不足时模型须如实说明；返回内容由 AiResponseParser 强校验，不合规抛异常。
     */
    AiResult organize(String rawInput, List<LinkMaterial> materials,
                      List<CategoryRow> categories, List<String> popularTags);

    /** 冷启动引导：基于抽样原文建议 6-8 个分类名。失败/不可用返回空列表，由调用方兜底。 */
    List<String> suggestCategories(List<String> sampleRawInputs);

    /** 实际使用的模型名，落 ai_model 列，便于排查"当时调的是哪个模型"。 */
    String modelName();
}
