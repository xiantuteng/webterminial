package com.jingzhou.webterminal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 入口固定在 /wt/。欢迎页按 Ctrl+Alt+Shift+L 切换到登录窗口。
 * 登录使用本机系统账号，终端和文件都限制在该账号的主目录范围内。
 */
@SpringBootApplication
public class WebTerminalApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebTerminalApplication.class, args);
    }
}
