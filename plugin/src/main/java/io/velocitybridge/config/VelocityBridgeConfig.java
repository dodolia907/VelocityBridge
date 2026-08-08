package io.velocitybridge.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * velocitybridge の設定。
 *
 * <p>{@code velocitybridge.conf}（YAML）から読み込む。各キーの意味：</p>
 * <ul>
 *   <li>{@code node-id}: 自ノードID</li>
 *   <li>{@code mode}: {@code leader} または {@code follower}</li>
 *   <li>{@code leader-address}: リーダーのハブアドレス（follower で必須）</li>
 *   <li>{@code hub-port}: リーダーのハブ待ち受けポート（leader で必須）</li>
 *   <li>{@code secret-file}: forwarding.secret のパス（未設定なら設定ファイルと同ディレクトリ）</li>
 *   <li>{@code proxies}: プロキシ定義のリスト</li>
 * </ul>
 *
 * @param nodeId          自ノードID
 * @param mode            leader / follower
 * @param leaderAddress   follower 用のリーダーハブアドレス（host:port）
 * @param hubPort         leader 用のハブ待ち受けポート
 * @param secret          ハブ認証シークレット
 * @param proxies         プロキシ定義
 */
public record VelocityBridgeConfig(
        String nodeId,
        String mode,
        String leaderAddress,
        int hubPort,
        String secret,
        List<ProxyInfo> proxies) {

    /** プロキシ1台分の定義。 */
    public record ProxyInfo(String id, String address, String region) {
    }

    /** 既定の設定ファイル名。 */
    public static final String CONFIG_FILE = "velocitybridge.conf";

    /**
     * データディレクトリから設定を読み込む。ファイルが無ければ既定値を書き出す。
     *
     * @param dataDirectory プラグインのデータディレクトリ
     * @param logger        ロガー
     * @return 設定
     */
    @SuppressWarnings("unchecked")
    public static VelocityBridgeConfig load(Path dataDirectory, Logger logger) throws IOException {
        Path configPath = dataDirectory.resolve(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            Files.createDirectories(dataDirectory);
            writeDefault(configPath);
            logger.info("Created default config at {}", configPath);
        }

        Map<String, Object> data;
        try (InputStream in = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml();
            Object root = yaml.load(in);
            data = root instanceof Map ? (Map<String, Object>) root : new LinkedHashMap<>();
        }

        String nodeId = asString(data, "node-id", "proxy-1");
        String mode = asString(data, "mode", "leader");
        String leaderAddress = asString(data, "leader-address", "");
        int hubPort = asInt(data, "hub-port", 51850);

        Path secretPath = dataDirectory.resolve("forwarding.secret");
        String secret = loadSecret(secretPath, logger);

        List<ProxyInfo> proxies = new ArrayList<>();
        Object proxiesObj = data.get("proxies");
        if (proxiesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    proxies.add(new ProxyInfo(
                            String.valueOf(map.get("id")),
                            String.valueOf(map.get("address")),
                            map.get("region") == null ? "" : String.valueOf(map.get("region"))));
                }
            }
        }

        return new VelocityBridgeConfig(nodeId, mode, leaderAddress, hubPort, secret, List.copyOf(proxies));
    }

    private static String asString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int asInt(Map<String, Object> data, String key, int defaultValue) {
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private static String loadSecret(Path secretPath, Logger logger) throws IOException {
        if (Files.exists(secretPath)) {
            String secret = Files.readString(secretPath, StandardCharsets.UTF_8).trim();
            if (!secret.isEmpty()) {
                return secret;
            }
        }
        logger.warn("forwarding.secret not found or empty; generating a random secret at {}", secretPath);
        String generated = java.util.UUID.randomUUID().toString().replace("-", "")
                + java.util.UUID.randomUUID().toString().replace("-", "");
        Files.writeString(secretPath, generated, StandardCharsets.UTF_8);
        return generated;
    }

    private static void writeDefault(Path configPath) throws IOException {
        String content = """
                # VelocityBridge configuration
                node-id: "proxy-1"
                # leader or follower
                mode: "leader"
                # Leader hub address (follower only), e.g. "127.0.0.1:51850"
                leader-address: ""
                # Hub listen port (leader only)
                hub-port: 51850

                # Backend proxies (all proxies share the same definitions)
                # address must be resolvable by clients for /vb transfer.
                proxies:
                  - id: "proxy-1"
                    address: "127.0.0.1:25565"
                    region: "Local"
                  - id: "proxy-2"
                    address: "127.0.0.1:25566"
                    region: "Local"
                """;
        try (OutputStream out = Files.newOutputStream(configPath)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
