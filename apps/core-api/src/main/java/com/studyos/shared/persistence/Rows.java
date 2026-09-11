package com.studyos.shared.persistence;

import java.sql.*;
import java.util.*;

public final class Rows {
    private Rows() {}

    public static Map<String, Object> map(ResultSet rs, int row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            String label = rs.getMetaData().getColumnLabel(i);
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char c : label.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    name.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            Object value = rs.getObject(i);
            if (value instanceof Timestamp t) value = t.toInstant();
            else if (value instanceof java.sql.Date d) value = d.toLocalDate();
            else if (value != null
                    && (rs.getMetaData().getColumnTypeName(i).equals("jsonb")
                            || rs.getMetaData().getColumnTypeName(i).equals("json")))
                value = Json.read(value.toString());
            else if (value != null
                    && value.getClass().getName().equals("org.postgresql.util.PGobject"))
                value = value.toString();
            result.put(name.toString(), value);
        }
        return result;
    }

    public static UUID uuid(Map<String, Object> row, String key) {
        return UUID.fromString(row.get(key).toString());
    }
}
