package io.github.nameof.watermark;

import io.github.nameof.watermark.bit.ColumnWatermarkStrategy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import static org.junit.Assert.*;

/**
 * 数据暗水印集成测试。
 * <p>
 * 完整流程：建表 → 插入公民数据 → 嵌入水印 → 回写 MySQL → 读回 → 提取水印。
 * 需要本地 MySQL 服务运行在 localhost:3306，用户名/密码为 root/root。
 * </p>
 */
public class DataWatermarkerIntegrationTest {

    private static final String PAYLOAD = "operator:zhangsan|company:ACME";
    private static final String SECRET_KEY = "my-secret-key-2024";
    private static final String TABLE_NAME = "citizen_info";

    private Connection conn;

    // ========== 公民测试数据（手工精选 30 行 + 程序化生成 770 行 = 800 行） ==========
    // 注意：水印算法需要足够的可嵌入单元格来保证冗余度。
    // 30 行×3 列=90 单元格，不足以承载 30 字节载荷（256 bits）。
    // 扩展到 800 行×3 列=2400 单元格，平均重复因子 ≈ 9，可可靠提取。
    private static final String[][] CITIZEN_DATA;
    static {
        String[][] base = {
                // {name, address, salary}
                {"张三",   "成都市天府大道1号",         "15000.50"},
                {"李四",   "北京市海淀区中关村大街1号",  "22000.00"},
                {"王五",   "上海市浦东新区陆家嘴路10号", "18500.75"},
                {"赵六",   "广州市天河区体育西路5号",    "12000.00"},
                {"孙七",   "深圳市南山区科技园路8号",    "25000.25"},
                {"周八",   "杭州市西湖区文三路99号",     "16000.00"},
                {"吴九",   "南京市鼓楼区中山路33号",     "19000.50"},
                {"郑十",   "武汉市武昌区珞喻路100号",    "14000.00"},
                {"陈小明", "重庆市渝中区解放碑步行街",    "17500.80"},
                {"林小红", "天津市和平区南京路55号",     "13500.00"},
                {"黄大伟", "西安市雁塔区长安南路1号",    "21000.00"},
                {"刘美丽", "长沙市岳麓区麓山南路20号",   "16500.30"},
                {"杨志强", "郑州市金水区花园路100号",    "18000.00"},
                {"何晓东", "苏州市姑苏区观前街50号",     "20000.60"},
                {"马丽丽", "青岛市市南区香港中路10号",    "15500.00"},
                {"朱建华", "大连市中山区人民路88号",     "17000.40"},
                {"许文静", "沈阳市沈河区中街路22号",     "14500.00"},
                {"吕国强", "济南市历下区泉城路15号",     "19500.90"},
                {"谢小梅", "合肥市蜀山区长江西路200号",   "16000.00"},
                {"韩鹏飞", "福州市鼓楼区八一七路33号",   "22500.00"},
                {"唐晓东", "昆明市盘龙区东风东路1号",    "13000.70"},
                {"冯雅琴", "太原市迎泽区迎泽大街80号",   "15000.00"},
                {"董明辉", "哈尔滨市南岗区中山路150号",  "18500.00"},
                {"萧红梅", "石家庄市桥西区中山路10号",    "12500.20"},
                {"程建国", "南昌市东湖区八一大道50号",   "17000.00"},
                {"曹晓燕", "长春市朝阳区人民大街1号",    "14000.50"},
                {"袁志强", "南宁市青秀区民族大道100号",  "16500.00"},
                {"邓小丽", "贵阳市南明区遵义路22号",     "13500.80"},
                {"彭海涛", "兰州市城关区中山路88号",     "19000.00"},
                {"潘晓芳", "呼和浩特市赛罕区大学东街5号", "11500.40"},
        };
        // 程序化生成额外 770 行，确保水印算法有足够的单元格容量（800行×3列=2400单元格）
        String[] surnames = {"张","王","李","赵","刘","陈","杨","黄","周","吴",
                             "徐","孙","马","朱","胡","林","郭","何","高","罗"};
        String[] givens = {"志强","建国","晓东","美丽","文明","海涛",
                           "雅琴","志明","晓燕","建华","红梅","鹏飞",
                           "文静","国强","小梅","大伟","小红","小明"};
        String[] cities = {"成都市","武汉市","南京市","长沙市","郑州市",
                           "济南市","合肥市","福州市","昆明市","太原市",
                           "南宁市","贵阳市","兰州市","南昌市","哈尔滨市"};
        String[] districts = {"天河区","海淀区","浦东新区","武昌区","岳麓区",
                              "金水区","蜀山区","鼓楼区","朝阳区","青秀区"};
        String[] streets = {"中山路","人民路","解放路","建设路","和平路",
                            "文化路","科技路","学府路","光明路","新华路"};
        int extraRows = 770;
        CITIZEN_DATA = new String[base.length + extraRows][3];
        System.arraycopy(base, 0, CITIZEN_DATA, 0, base.length);
        for (int i = 0; i < extraRows; i++) {
            int idx = base.length + i;
            String surname = surnames[i % surnames.length];
            String given = givens[(i * 7 + 3) % givens.length];
            String city = cities[(i * 3 + 1) % cities.length];
            String district = districts[(i * 5 + 2) % districts.length];
            String street = streets[(i * 7 + 4) % streets.length];
            int num = (i * 13 + 7) % 200 + 1;
            int salary = 8000 + ((i * 1234 + 5678) % 20000);
            CITIZEN_DATA[idx] = new String[]{
                    surname + given,
                    city + district + street + num + "号",
                    String.valueOf(salary) + ".00"
            };
        }
    }

    @Before
    public void setUp() throws Exception {
        Properties props = loadTestConfig();
        String url = props.getProperty("db.url");
        String username = props.getProperty("db.username");
        String password = props.getProperty("db.password");
        String database = props.getProperty("db.database");

        // 先连接（不指定数据库）创建目标数据库
        String baseUrl = url.substring(0, url.indexOf('/', url.indexOf("//") + 2));
        // baseUrl = jdbc:mysql://localhost:3306/
        try (Connection setupConn = DriverManager.getConnection(
                baseUrl + "?useSSL=false&allowPublicKeyRetrieval=true", username, password)) {
            setupConn.createStatement().executeUpdate(
                    "CREATE DATABASE IF NOT EXISTS `" + database
                            + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        // 连接到目标数据库
        conn = DriverManager.getConnection(url, username, password);

        // 建表
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE IF EXISTS `" + TABLE_NAME + "`");
            stmt.executeUpdate(
                    "CREATE TABLE `" + TABLE_NAME + "` ("
                            + "  `id`       INT           NOT NULL AUTO_INCREMENT,"
                            + "  `name`     VARCHAR(100)  NOT NULL,"
                            + "  `address`  VARCHAR(500)  NOT NULL,"
                            + "  `salary`   DECIMAL(10,2) NOT NULL,"
                            + "  PRIMARY KEY (`id`)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
        }

        // 插入测试数据
        String insertSql = "INSERT INTO `" + TABLE_NAME
                + "` (`name`, `address`, `salary`) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (String[] row : CITIZEN_DATA) {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.setBigDecimal(3, new BigDecimal(row[2]));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        System.out.println("=== 测试环境初始化完成，已插入 " + CITIZEN_DATA.length + " 条公民数据 ===");
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP TABLE IF EXISTS `" + TABLE_NAME + "`");
            }
            conn.close();
        }
    }

    // ==================== 测试用例 ====================

    /**
     * 完整水印流程测试：嵌入 → 回写 MySQL → 读回 → 提取。
     */
    @Test
    public void testFullWatermarkRoundTrip() throws Exception {
        System.out.println("\n========== 测试：完整水印嵌入/提取流程 ==========");

        // 1. 从 MySQL 读取原始数据
        List<Map<String, Object>> originalTable = readTableData();
        System.out.println("[1] 从数据库读取 " + originalTable.size() + " 行原始数据");
        printTable(originalTable, 3);

        // 2. 配置水印器并检测支持情况
        WatermarkConfig config = new WatermarkConfig(SECRET_KEY);
        DataWatermarker watermarker = new DataWatermarker(config);
        List<String> watermarkColumns = Arrays.asList("name", "address", "salary");

        boolean supported = watermarker.supportsWatermark(originalTable, watermarkColumns);
        assertTrue("应该支持水印嵌入", supported);

        Map<String, ColumnWatermarkStrategy> columnStrategies =
                watermarker.detectWatermarkColumns(originalTable, watermarkColumns);
        System.out.println("[2] 检测到可水印列: " + columnStrategies.keySet());

        // 3. 嵌入水印
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(originalTable, watermarkColumns, PAYLOAD);
        assertTrue("嵌入应成功: " + embedResult.getMessage(), embedResult.isSuccess());
        System.out.println("[3] 水印嵌入成功，载荷: " + PAYLOAD);
        System.out.println("    涉及列: " + embedResult.getWatermarkedColumns());
        System.out.println("    重复因子: " + embedResult.getRepetition());

        List<Map<String, Object>> watermarkedTable = embedResult.getData();

        // 4. 展示水印前后的差异
        System.out.println("\n[4] 水印前后对比（前5行）:");
        for (int i = 0; i < Math.min(5, watermarkedTable.size()); i++) {
            Map<String, Object> orig = originalTable.get(i);
            Map<String, Object> wm = watermarkedTable.get(i);
            System.out.println("  行" + (i + 1) + ":");
            System.out.println("    name:    [" + orig.get("name") + "] → [" + wm.get("name") + "]");
            System.out.println("    address: [" + orig.get("address") + "] → [" + wm.get("address") + "]");
            System.out.println("    salary:  [" + orig.get("salary") + "] → [" + wm.get("salary") + "]");
        }

        // 5. 将水印数据回写 MySQL
        writeBackToDatabase(watermarkedTable);
        System.out.println("\n[5] 水印数据已回写 MySQL");

        // 6. 从 MySQL 重新读回
        List<Map<String, Object>> rereadTable = readTableData();
        System.out.println("[6] 从数据库重新读回 " + rereadTable.size() + " 行数据");

        // 7. 提取水印
        WatermarkResult<String> extractResult = watermarker.extract(rereadTable, watermarkColumns);
        assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("提取的水印载荷应与原始一致", PAYLOAD, extractResult.getData());
        System.out.println("[7] 水印提取成功！还原载荷: " + extractResult.getData());

        System.out.println("\n========== 完整流程测试通过 ==========");
    }

    /**
     * 健壮性测试：删除部分行后仍能提取水印。
     */
    @Test
    public void testRobustnessAfterRowDeletion() throws Exception {
        System.out.println("\n========== 测试：删除部分行后的健壮性 ==========");

        // 1. 嵌入水印并回写
        List<Map<String, Object>> originalTable = readTableData();
        WatermarkConfig config = new WatermarkConfig(SECRET_KEY);
        DataWatermarker watermarker = new DataWatermarker(config);
        List<String> watermarkColumns = Arrays.asList("name", "address", "salary");

        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(originalTable, watermarkColumns, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());
        writeBackToDatabase(embedResult.getData());
        System.out.println("[1] 水印数据已回写，共 " + embedResult.getData().size() + " 行");

        // 2. 删除 30% 的行（从末尾删除）
        //    交叉分配方案下，每个 bit 的副本均匀分布，删除 30% 后每 bit 仍有 ~5 个副本
        int totalCount = CITIZEN_DATA.length;
        int deleteCount = (int) (totalCount * 0.3);
        int keepCount = totalCount - deleteCount;
        try (Statement stmt = conn.createStatement()) {
            int deleted = stmt.executeUpdate(
                    "DELETE FROM `" + TABLE_NAME + "` WHERE id > " + keepCount);
            System.out.println("[2] 已删除 " + deleted + " 行（id > " + keepCount + "）");
        }

        // 3. 读回剩余数据
        List<Map<String, Object>> partialTable = readTableData();
        System.out.println("[3] 剩余 " + partialTable.size() + " 行数据");

        // 4. 尝试提取水印
        WatermarkResult<String> extractResult = watermarker.extract(partialTable, watermarkColumns);
        assertTrue("删除 " + deleteCount + " 行后仍应能提取水印: " + extractResult.getMessage(),
                extractResult.isSuccess());
        assertEquals("提取的水印载荷应与原始一致", PAYLOAD, extractResult.getData());
        System.out.println("[4] 健壮性测试通过！删除 " + deleteCount + " 行后仍成功还原: "
                + extractResult.getData());

        System.out.println("\n========== 健壮性测试通过 ==========");
    }

    /**
     * 纯内存测试：不经过数据库，验证 embed → extract 的基本正确性。
     */
    @Test
    public void testInMemoryEmbedAndExtract() {
        System.out.println("\n========== 测试：纯内存嵌入/提取 ==========");

        // 构造内存数据
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < CITIZEN_DATA.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i + 1);
            row.put("name", CITIZEN_DATA[i][0]);
            row.put("address", CITIZEN_DATA[i][1]);
            row.put("salary", new BigDecimal(CITIZEN_DATA[i][2]));
            table.add(row);
        }

        WatermarkConfig config = new WatermarkConfig(SECRET_KEY);
        DataWatermarker watermarker = new DataWatermarker(config);
        List<String> columns = Arrays.asList("name", "address", "salary");

        // 嵌入
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        // 提取
        WatermarkResult<String> extractResult =
                watermarker.extract(embedResult.getData(), columns);
        assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals(PAYLOAD, extractResult.getData());

        System.out.println("纯内存测试通过！还原载荷: " + extractResult.getData());
        System.out.println("========== 纯内存测试通过 ==========");
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 MySQL 读取测试表的全部数据。
     */
    private List<Map<String, Object>> readTableData() throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT id, name, address, salary FROM `" + TABLE_NAME + "` ORDER BY id";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 将加水印后的数据回写到 MySQL。
     */
    private void writeBackToDatabase(List<Map<String, Object>> watermarkedTable) throws SQLException {
        String updateSql = "UPDATE `" + TABLE_NAME
                + "` SET name = ?, address = ?, salary = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            for (Map<String, Object> row : watermarkedTable) {
                ps.setString(1, String.valueOf(row.get("name")));
                ps.setString(2, String.valueOf(row.get("address")));

                // salary 可能是 BigDecimal / Double / String，统一处理
                Object salary = row.get("salary");
                if (salary instanceof BigDecimal) {
                    ps.setBigDecimal(3, (BigDecimal) salary);
                } else if (salary instanceof Number) {
                    ps.setDouble(3, ((Number) salary).doubleValue());
                } else {
                    // 防御性处理：去除可能混入的零宽字符后再解析
                    String salaryStr = salary.toString()
                            .replaceAll("[\\u200B-\\u200F]", "");
                    ps.setBigDecimal(3, new BigDecimal(salaryStr));
                }

                ps.setInt(4, ((Number) row.get("id")).intValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 打印表数据的前 N 行（用于调试）。
     */
    private void printTable(List<Map<String, Object>> table, int maxRows) {
        System.out.printf("  %-4s %-10s %-30s %-12s%n", "id", "name", "address", "salary");
        System.out.println("  ------------------------------------------------------------");
        for (int i = 0; i < Math.min(maxRows, table.size()); i++) {
            Map<String, Object> row = table.get(i);
            System.out.printf("  %-4s %-10s %-30s %-12s%n",
                    row.get("id"),
                    visualize(row.get("name")),
                    visualize(row.get("address")),
                    row.get("salary"));
        }
        if (table.size() > maxRows) {
            System.out.println("  ... 共 " + table.size() + " 行");
        }
    }

    /**
     * 将字符串中的不可见字符可视化（用于调试水印效果）。
     */
    private String visualize(Object value) {
        if (value == null) return "null";
        String str = value.toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\u200B') {
                sb.append("⟨ZWS⟩");
            } else if (c == '\u200C') {
                sb.append("⟨ZWNJ⟩");
            } else if (c >= 0x0400 && c <= 0x04FF) {
                sb.append("⟨").append(String.format("U+%04X", (int) c)).append("⟩");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 加载测试配置。
     */
    private Properties loadTestConfig() throws Exception {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("test-config.properties")) {
            if (is == null) {
                throw new RuntimeException("找不到 test-config.properties，请确认文件在 src/test/resources 下");
            }
            props.load(is);
        }
        return props;
    }
}
