package org.gms.httpapi.docs;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 由代码生成的管理/内部 API 文档元数据。
 *
 * <p>公共第三方 API 的发布契约仍按主版本独立冻结；本定义负责让实际 Micronaut
 * 路由产生可浏览、可校验的 OpenAPI 产物，供契约差异检查和内部文档使用。
 */
@OpenAPIDefinition(
        info = @Info(
                title = "Twinkle HTTP API",
                version = "0.1.0",
                description = "Versioned public, console administration and internal operation APIs"
        ),
        tags = {
                @Tag(name = "Public API", description = "Third-party versioned API"),
                @Tag(name = "Admin API", description = "Web console administration API"),
                @Tag(name = "Internal API", description = "Private operation API")
        }
)
public final class OpenApiDocumentation {

    private OpenApiDocumentation() {
    }
}
