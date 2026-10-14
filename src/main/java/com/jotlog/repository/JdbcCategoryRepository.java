package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class JdbcCategoryRepository implements CategoryRepository {

    private final JdbcTemplate jdbc;

    public JdbcCategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT c.id, c.name, c.color, c.sort_order, c.is_system,
                   (SELECT COUNT(*) FROM entries e
                     WHERE e.category_id = c.id AND e.archived = 0) AS cnt
            FROM categories c
            """;

    @Override
    public List<CategoryRow> list() {
        return jdbc.query(SELECT + " ORDER BY c.sort_order ASC, c.id ASC",
                JdbcCategoryRepository::map);
    }

    @Override
    public List<CategoryRow> listAll() {
        return list();
    }

    @Override
    public CategoryRow findById(long id) {
        var rows = jdbc.query(SELECT + " WHERE c.id = ?", JdbcCategoryRepository::map, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public CategoryRow findByName(String name) {
        var rows = jdbc.query(SELECT + " WHERE c.name = ?", JdbcCategoryRepository::map, name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public long insert(String name, String color) {
        Integer maxOrder = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM categories", Integer.class);
        jdbc.update("INSERT INTO categories (name, color, sort_order, is_system) VALUES (?, ?, ?, 0)",
                name, color, (maxOrder == null ? 0 : maxOrder) + 1);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    @Override
    public void rename(long id, String name, String color) {
        jdbc.update("UPDATE categories SET name = ?, color = COALESCE(?, color) WHERE id = ?",
                name, color, id);
    }

    @Override
    public long uncategorizedCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM entries WHERE category_id IS NULL AND archived = 0", Long.class);
        return n == null ? 0 : n;
    }

    private static CategoryRow map(ResultSet rs, int i) throws SQLException {
        return new CategoryRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("color"),
                rs.getInt("sort_order"),
                rs.getBoolean("is_system"),
                rs.getLong("cnt"));
    }
}
