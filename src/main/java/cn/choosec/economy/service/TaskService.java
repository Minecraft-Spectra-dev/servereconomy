package cn.choosec.economy.service;

import cn.choosec.economy.database.DatabaseManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Server task pool: admins define task templates (type, target, required amount,
 * reward) that the daily-task system draws from per player and per day.
 */
public final class TaskService {

    public record Task(int id, String name, String description, String type, String target,
                       int amount, BigDecimal reward, boolean enabled) {
    }

    private TaskService() {
    }

    public static synchronized int addTask(String name, String description, String type, String target,
                                           int amount, BigDecimal reward) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO tasks (name, description, type, target, amount, reward, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, 1)""", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, description);
                ps.setString(3, type);
                ps.setString(4, target);
                ps.setInt(5, amount);
                ps.setBigDecimal(6, reward);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return -1;
    }

    public static synchronized List<Task> listTasks() {
        List<Task> result = new ArrayList<>();
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM tasks ORDER BY id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(row(rs));
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return result;
    }

    public static synchronized Task getTask(int id) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM tasks WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return null;
        }
    }

    public static synchronized boolean deleteTask(int id) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement d = c.prepareStatement("DELETE FROM daily_tasks WHERE task_id = ?")) {
                d.setInt(1, id);
                d.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
                ps.setInt(1, id);
                boolean deleted = ps.executeUpdate() > 0;
                if (deleted) {
                    DailyTaskService.invalidateAll();
                }
                return deleted;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    private static Task row(ResultSet rs) throws SQLException {
        return new Task(rs.getInt("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("type"), rs.getString("target"), rs.getInt("amount"),
                rs.getBigDecimal("reward"), rs.getInt("enabled") == 1);
    }
}
