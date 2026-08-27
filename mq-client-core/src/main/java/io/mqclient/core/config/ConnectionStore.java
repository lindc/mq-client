package io.mqclient.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.mqclient.core.model.MQConnectionConfig;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 连接配置存储 — JSON 文件持久化。
 * <p>
 * 密码字段由各 MQ 的 config editor 自行加密（如 AES），
 * ConnectionStore 只做存储不关心加密逻辑。
 */
@Component
public class ConnectionStore {

    private static final File DEFAULT_FILE = new File(
            System.getProperty("user.home"), ".mqclient/connections.json");

    private final File file;
    private final ObjectMapper mapper;
    private final List<MQConnectionConfig> connections;
    private final List<Runnable> listeners;

    public ConnectionStore() {
        this(DEFAULT_FILE);
    }

    public ConnectionStore(File file) {
        this.file = file;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.connections = new CopyOnWriteArrayList<>();
        this.listeners = new CopyOnWriteArrayList<>();
        load();
    }

    // ── 读 ──

    public List<MQConnectionConfig> listAll() {
        return Collections.unmodifiableList(connections);
    }

    public MQConnectionConfig findById(String id) {
        return connections.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst().orElse(null);
    }

    public List<MQConnectionConfig> findByType(String type) {
        return connections.stream()
                .filter(c -> type.equals(c.getType()))
                .toList();
    }

    // ── 写 ──

    public void add(MQConnectionConfig config) {
        connections.add(config);
        save();
        notifyListeners();
    }

    public void update(MQConnectionConfig config) {
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).getId().equals(config.getId())) {
                connections.set(i, config);
                save();
                notifyListeners();
                return;
            }
        }
    }

    public void remove(String id) {
        connections.removeIf(c -> c.getId().equals(id));
        save();
        notifyListeners();
    }

    // ── 持久化 ──

    private void load() {
        if (!file.exists()) return;
        try {
            file.getParentFile().mkdirs();
            List<MQConnectionConfig> loaded = mapper.readValue(
                    file, new TypeReference<List<MQConnectionConfig>>() {});
            if (loaded != null) {
                connections.clear();
                connections.addAll(loaded);
            }
        } catch (IOException e) {
            System.err.println("[ConnectionStore] Failed to load: " + e.getMessage());
        }
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            mapper.writeValue(file, connections);
        } catch (IOException e) {
            System.err.println("[ConnectionStore] Failed to save: " + e.getMessage());
        }
    }

    // ── 变更监听 ──

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        listeners.forEach(Runnable::run);
    }
}
