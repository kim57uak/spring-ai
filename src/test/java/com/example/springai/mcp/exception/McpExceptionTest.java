package com.example.springai.mcp.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

import com.example.springai.exception.McpClientCreationException;
import com.example.springai.exception.McpException;
import com.example.springai.exception.McpProcessLaunchException;
import com.example.springai.exception.McpToolCallException;
import com.example.springai.exception.McpValidationException;

class McpExceptionTest {

    @Test
    void shouldCreateMcpExceptionWithMessage() {
        // Given
        String message = "Test error message";

        // When
        McpException exception = new McpException(message);

        // Then
        assertThat(exception).hasMessage(message);
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void shouldCreateMcpExceptionWithMessageAndCause() {
        // Given
        String message = "Test error message";
        Throwable cause = new RuntimeException("Cause");

        // When
        McpException exception = new McpException(message, cause);

        // Then
        assertThat(exception)
                .hasMessage(message)
                .hasCause(cause);
    }

    @Test
    void shouldCreateMcpClientCreationException() {
        // Given
        String serverName = "test-server";
        String message = "Failed to connect";

        // When
        McpClientCreationException exception = new McpClientCreationException(serverName, message);

        // Then
        assertThat(exception.getServerName()).isEqualTo(serverName);
        assertThat(exception.getMessage())
                .contains(serverName)
                .contains(message);
    }

    @Test
    void shouldCreateMcpClientCreationExceptionWithCause() {
        // Given
        String serverName = "test-server";
        String message = "Failed to connect";
        Throwable cause = new RuntimeException("Connection refused");

        // When
        McpClientCreationException exception = new McpClientCreationException(serverName, message, cause);

        // Then
        assertThat(exception.getServerName()).isEqualTo(serverName);
        assertThat(exception.getMessage())
                .contains(serverName)
                .contains(message);
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    void shouldCreateMcpProcessLaunchException() {
        // Given
        String serverName = "python-server";
        String message = "Process failed to start";

        // When
        McpProcessLaunchException exception = new McpProcessLaunchException(serverName, message);

        // Then
        assertThat(exception.getServerName()).isEqualTo(serverName);
        assertThat(exception.getMessage())
                .contains(serverName)
                .contains(message);
    }

    @Test
    void shouldCreateMcpProcessLaunchExceptionWithCause() {
        // Given
        String serverName = "python-server";
        String message = "Process failed to start";
        Throwable cause = new java.io.IOException("File not found");

        // When
        McpProcessLaunchException exception = new McpProcessLaunchException(serverName, message, cause);

        // Then
        assertThat(exception.getServerName()).isEqualTo(serverName);
        assertThat(exception.getMessage())
                .contains(serverName)
                .contains(message);
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    void shouldCreateMcpToolCallException() {
        // Given
        String toolName = "search_tool";
        String message = "API key invalid";

        // When
        McpToolCallException exception = new McpToolCallException(toolName, message);

        // Then
        assertThat(exception.getToolName()).isEqualTo(toolName);
        assertThat(exception.getMessage())
                .contains(toolName)
                .contains(message);
    }

    @Test
    void shouldCreateMcpToolCallExceptionWithCause() {
        // Given
        String toolName = "search_tool";
        String message = "API key invalid";
        Throwable cause = new IllegalArgumentException("Invalid key format");

        // When
        McpToolCallException exception = new McpToolCallException(toolName, message, cause);

        // Then
        assertThat(exception.getToolName()).isEqualTo(toolName);
        assertThat(exception.getMessage())
                .contains(toolName)
                .contains(message);
        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    void shouldCreateMcpValidationException() {
        // Given
        String validationType = "command";
        String message = "Contains dangerous characters";

        // When
        McpValidationException exception = new McpValidationException(validationType, message);

        // Then
        assertThat(exception.getValidationType()).isEqualTo(validationType);
        assertThat(exception.getMessage())
                .contains(validationType)
                .contains(message);
    }

    @Test
    void shouldBeRuntimeException() {
        // When
        McpException exception = new McpException("test");

        // Then
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldMaintainExceptionHierarchy() {
        // Given
        McpClientCreationException clientException = new McpClientCreationException("server", "message");
        McpProcessLaunchException processException = new McpProcessLaunchException("server", "message");
        McpToolCallException toolException = new McpToolCallException("tool", "message");
        McpValidationException validationException = new McpValidationException("type", "message");

        // Then - All should be McpException
        assertThat(clientException).isInstanceOf(McpException.class);
        assertThat(processException).isInstanceOf(McpException.class);
        assertThat(toolException).isInstanceOf(McpException.class);
        assertThat(validationException).isInstanceOf(McpException.class);
    }

    @Test
    void shouldProvideUsefulErrorMessagesForClientCreation() {
        // Given
        String serverName = "search-server";
        String errorDetail = "Connection timeout";

        // When
        McpClientCreationException exception = new McpClientCreationException(serverName, errorDetail);

        // Then
        assertThat(exception.getMessage())
                .contains("Failed to create MCP client")
                .contains(serverName)
                .contains(errorDetail);
    }

    @Test
    void shouldProvideUsefulErrorMessagesForProcessLaunch() {
        // Given
        String serverName = "economy-server";
        String errorDetail = "Python executable not found";

        // When
        McpProcessLaunchException exception = new McpProcessLaunchException(serverName, errorDetail);

        // Then
        assertThat(exception.getMessage())
                .contains("Failed to launch MCP process")
                .contains(serverName)
                .contains(errorDetail);
    }

    @Test
    void shouldProvideUsefulErrorMessagesForToolCall() {
        // Given
        String toolName = "get_stock_price";
        String errorDetail = "Stock symbol not found";

        // When
        McpToolCallException exception = new McpToolCallException(toolName, errorDetail);

        // Then
        assertThat(exception.getMessage())
                .contains("MCP tool call failed")
                .contains(toolName)
                .contains(errorDetail);
    }

    @Test
    void shouldProvideUsefulErrorMessagesForValidation() {
        // Given
        String validationType = "environment";
        String errorDetail = "Invalid variable name format";

        // When
        McpValidationException exception = new McpValidationException(validationType, errorDetail);

        // Then
        assertThat(exception.getMessage())
                .contains("MCP validation failed")
                .contains(validationType)
                .contains(errorDetail);
    }
}
