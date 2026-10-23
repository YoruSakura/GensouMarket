package net.scarletphantasy.gensouMarket.storage;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SchemaFailureTest {
    @Test void sqliteInspectionFailurePropagates() throws Exception {
        var storage = new SQLiteStorage(new File("unused"));
        var connection = mock(Connection.class);
        var field = SQLiteStorage.class.getDeclaredField("connection");
        field.setAccessible(true);
        field.set(storage, connection);
        when(connection.createStatement()).thenThrow(new SQLException("inspection failed"));
        var method = SQLiteStorage.class.getDeclaredMethod("addColumnIfMissing", String.class, String.class, String.class);
        method.setAccessible(true);
        var error = assertThrows(InvocationTargetException.class,
                () -> method.invoke(storage, "shop_data", "mode", "TEXT"));
        assertInstanceOf(SQLException.class, error.getCause());
    }

    @Test void sqliteAlterFailurePropagatesUnlessAnotherServerAddedColumn() throws Exception {
        var storage = new SQLiteStorage(new File("unused"));
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var absent = mock(ResultSet.class);
        var present = mock(ResultSet.class);
        when(present.next()).thenReturn(true);
        when(present.getString("name")).thenReturn("mode");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(absent, absent, absent, present);
        when(statement.executeUpdate(anyString())).thenThrow(new SQLException("ALTER failed"));
        var field = SQLiteStorage.class.getDeclaredField("connection");
        field.setAccessible(true);
        field.set(storage, connection);
        var method = SQLiteStorage.class.getDeclaredMethod("addColumnIfMissing", String.class, String.class, String.class);
        method.setAccessible(true);
        assertThrows(InvocationTargetException.class, () -> method.invoke(storage, "shop_data", "mode", "TEXT"));
        assertDoesNotThrow(() -> method.invoke(storage, "shop_data", "mode", "TEXT"));
    }

    @Test void mysqlInspectionFailurePropagates() throws Exception {
        var storage = new MySQLStorage("unused", 3306, "unused", "unused", "unused");
        var connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("inspection failed"));
        var method = MySQLStorage.class.getDeclaredMethod("addColumnIfMissing", Connection.class, String.class, String.class, String.class);
        method.setAccessible(true);
        var error = assertThrows(InvocationTargetException.class,
                () -> method.invoke(storage, connection, "shop_data", "mode", "VARCHAR(16)"));
        assertInstanceOf(SQLException.class, error.getCause());
    }
}
