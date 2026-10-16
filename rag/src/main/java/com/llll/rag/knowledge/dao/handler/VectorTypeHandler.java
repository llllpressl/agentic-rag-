package com.llll.rag.knowledge.dao.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.StringJoiner;

/**
 * PostgreSQL vector 与 Java float[] 的转换。
 */
/// TODO AI写的
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index,
                                    float[] parameter, JdbcType jdbcType) throws SQLException {
        StringJoiner vector = new StringJoiner(",", "[", "]");
        for (float value : parameter) {
            vector.add(Float.toString(value));
        }
        statement.setObject(index, vector.toString(), Types.OTHER);
    }

    @Override
    public float[] getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return parse(resultSet.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return parse(resultSet.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return parse(statement.getString(columnIndex));
    }

    private float[] parse(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (!value.startsWith("[") || !value.endsWith("]")) {
            throw new SQLException("Invalid PostgreSQL vector: expected bracketed values");
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] elements = body.split(",", -1);
        float[] vector = new float[elements.length];
        try {
            for (int i = 0; i < elements.length; i++) {
                vector[i] = Float.parseFloat(elements[i].trim());
            }
        } catch (NumberFormatException exception) {
            throw new SQLException("Invalid PostgreSQL vector element", exception);
        }
        return vector;
    }
}
