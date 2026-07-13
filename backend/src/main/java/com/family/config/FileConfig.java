package com.family.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 文件配置类
 * <p>
 * 配置静态资源映射，将上传的文件通过 HTTP 直接访问
 */
@Configuration
public class FileConfig implements WebMvcConfigurer {

    /**
     * 文件上传路径
     */
    @Value("${file.upload-path:./uploads}")
    private String uploadPath;

    /**
     * 添加静态资源映射
     * <p>
     * 将 /uploads/** 请求映射到文件系统的上传目录
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath + "/");
    }
}
