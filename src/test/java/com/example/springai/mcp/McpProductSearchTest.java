package com.example.springai.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
public class McpProductSearchTest {

    @Autowired
    private McpClientFactory mcpClientFactory;

    @Test
    void searchProduct() {
        String productCode = "AAX20126042600P";
        McpClient client = mcpClientFactory.createClient("sale-product");
        
        Map<String, Object> params = Map.of(
            "request", Map.of(
                "guid", "search-test-guid",
                "saleProdCd", productCode
            )
        );
        
        System.out.println("Searching for product: " + productCode);
        String result = client.callTool("getSaleProductDetails", params);
        System.out.println("Search Result: " + result);
        
        assertThat(result).isNotBlank();
    }
}
