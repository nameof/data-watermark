package io.github.nameof.watermark.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CSV 数据源实现。
 * <p>
 * 使用纯 Java IO 读写 CSV 文件，不依赖第三方库。
 * 支持引号转义、逗号转义、换行转义等标准 CSV 特性。
 * 写入时使用 UTF-8 with BOM，兼容 Excel 打开中文内容。
 * </p>
 */
public class CsvDataSource implements DataSource {

    /** CSV 文件所在目录 */
    private final String baseDir;

    /**
     * 创建 CSV 数据源。
     *
     * @param baseDir CSV 文件所在目录（读写都在此目录下）
     */
    public CsvDataSource(String baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public TableData readTable(String tableName) throws Exception {
        return readTable(tableName, null);
    }

    @Override
    public TableData readTable(String tableName, String whereClause) throws Exception {
        File file = resolveFile(tableName);
        if (!file.exists()) {
            throw new FileNotFoundException("CSV 文件不存在: " + file.getAbsolutePath());
        }

        List<String> columnNames = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            // 跳过 BOM
            reader.mark(1);
            int first = reader.read();
            if (first != 0xFEFF) {
                reader.reset();
            }

            // 读取表头
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new TableData(tableName, columnNames, rows);
            }
            columnNames = parseCsvLine(headerLine);

            // 读取数据行
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> values = parseCsvLine(line);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columnNames.size(); i++) {
                    String val = i < values.size() ? values.get(i) : "";
                    row.put(columnNames.get(i), tryParseNumber(val));
                }
                rows.add(row);
            }
        }

        // whereClause 在 CSV 中不支持，忽略并记录警告
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            System.err.println("警告: CSV 数据源不支持 WHERE 子句，已忽略: " + whereClause);
        }

        return new TableData(tableName, columnNames, rows);
    }

    @Override
    public void writeTable(TableData data, String newTableName) throws Exception {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("表数据为空");
        }

        File file = resolveFile(newTableName);
        List<String> columns = data.getColumnNames();
        List<Map<String, Object>> rows = data.getRows();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {

            // 写入 UTF-8 BOM
            writer.write('\uFEFF');

            // 写入表头
            writer.write(joinCsvLine(columns));
            writer.newLine();

            // 写入数据行
            for (Map<String, Object> row : rows) {
                List<String> values = new ArrayList<>();
                for (String col : columns) {
                    Object val = row.get(col);
                    values.add(val != null ? val.toString() : "");
                }
                writer.write(joinCsvLine(values));
                writer.newLine();
            }
        }
    }

    @Override
    public void createTableLike(String sourceTable, String newTable) throws Exception {
        // CSV 不支持表结构，仅复制表头
        TableData source = readTable(sourceTable);
        File file = resolveFile(newTable);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write('\uFEFF');
            writer.write(joinCsvLine(source.getColumnNames()));
            writer.newLine();
        }
    }

    @Override
    public void close() throws IOException {
        // CSV 数据源无需关闭
    }

    // ==================== 内部方法 ====================

    /**
     * 根据表名解析为文件路径。
     * 如果 tableName 已包含 .csv 后缀则直接使用，否则追加 .csv。
     */
    private File resolveFile(String tableName) {
        String fileName = tableName.endsWith(".csv") ? tableName : tableName + ".csv";
        return new File(baseDir, fileName);
    }

    /**
     * 解析一行 CSV 文本为字段列表。
     * 支持双引号包裹的字段、字段内逗号和换行。
     */
    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        // 转义的双引号
                        current.append('"');
                        i++;
                    } else {
                        // 结束引号
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString().trim());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }

    /**
     * 将字段列表拼接为 CSV 行。
     * 包含逗号、双引号或换行的字段用双引号包裹。
     */
    private String joinCsvLine(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            String field = fields.get(i);
            if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
                sb.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(field);
            }
        }
        return sb.toString();
    }

    /**
     * 尝试将字符串解析为数字，失败则保持为字符串。
     */
    private Object tryParseNumber(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            long l = Long.parseLong(value);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
