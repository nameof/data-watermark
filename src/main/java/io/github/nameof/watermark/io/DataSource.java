package io.github.nameof.watermark.io;

import java.io.Closeable;

/**
 * 数据源接口，抽象表数据的读写操作。
 * <p>
 * 实现类可以是 JDBC 数据库、CSV 文件、Excel 文件等。
 * 水印引擎通过此接口与底层存储解耦。
 * </p>
 */
public interface DataSource extends Closeable {

    /**
     * 读取指定表的全部数据。
     *
     * @param tableName 表名（或文件名）
     * @return 表数据
     * @throws Exception 读取失败
     */
    TableData readTable(String tableName) throws Exception;

    /**
     * 读取指定表的部分数据（带 WHERE 条件）。
     *
     * @param tableName  表名（或文件名）
     * @param whereClause SQL WHERE 子句（不含 WHERE 关键字）
     * @return 表数据
     * @throws Exception 读取失败
     */
    TableData readTable(String tableName, String whereClause) throws Exception;

    /**
     * 将表数据写入到新的目标。
     * <p>
     * 对于数据库，会创建新表并插入数据。
     * 对于文件，会写入新文件。
     * </p>
     *
     * @param data         要写入的表数据
     * @param newTableName 新表名（或文件名）
     * @throws Exception 写入失败
     */
    void writeTable(TableData data, String newTableName) throws Exception;

    /**
     * 创建一张与源表结构相同的新表（仅结构，不含数据）。
     * <p>
     * 主要用于数据库场景，文件数据源可能不支持此操作。
     * </p>
     *
     * @param sourceTable 源表名
     * @param newTable    新表名
     * @throws Exception 创建失败
     */
    void createTableLike(String sourceTable, String newTable) throws Exception;
}
