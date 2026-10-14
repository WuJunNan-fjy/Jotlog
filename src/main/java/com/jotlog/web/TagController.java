package com.jotlog.web;

import com.jotlog.repository.TagRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 标签联想接口。标签的增删挂在条目接口上（/entries/{id}/tags）。 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagRepository tags;

    public TagController(TagRepository tags) {
        this.tags = tags;
    }

    @GetMapping
    public List<String> suggest(@RequestParam(required = false) String q) {
        return tags.suggest(q, 20);
    }
}
