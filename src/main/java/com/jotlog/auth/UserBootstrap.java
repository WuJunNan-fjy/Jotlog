package com.jotlog.auth;

import com.jotlog.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 首次启动建号。
 *
 * 单用户自托管没有注册入口，第一个账号只能由配置生成。
 * 生成完之后配置里的密码就不再生效了 —— 之后改密码走数据库，
 * 避免"配置文件里躺着一个明文密码"这种长期风险。
 *
 * 用 CommandLineRunner 而不是 @PostConstruct，是为了确保 Flyway
 * 已经把 users 表建好。PostConstruct 在 Bean 初始化时就跑，
 * 那时候迁移不一定执行完。
 */
@Component
public class UserBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final UserRepository users;
    private final AuthProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserBootstrap(UserRepository users, AuthProperties props) {
        this.users = users;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        if (!props.isBootstrap()) {
            log.info("jotlog.auth.bootstrap=false，跳过初始用户创建");
            return;
        }
        if (users.count() > 0) {
            return;
        }

        String username = props.getUsername().isBlank() ? "admin" : props.getUsername();
        String email = props.getEmail();
        String password = props.getPassword();
        boolean generated = false;

        if (password.isBlank()) {
            password = randomPassword();
            generated = true;
        }

        users.insert(username, email, encoder.encode(password), username);

        log.warn("=========================================================");
        log.warn("已创建初始用户：username={} email={}", username, email.isBlank() ? "(未配置)" : email);
        if (generated) {
            log.warn("未配置 jotlog.auth.password，已生成随机初始密码：{}", password);
        }
        log.warn("登录后请立刻在设置页改掉这个密码。");
        if (email.isBlank()) {
            log.warn("未配置 jotlog.auth.email，登录验证码将无处可发。");
            log.warn("请先执行：UPDATE users SET email='你的邮箱' WHERE username='{}';", username);
        }
        log.warn("=========================================================");
    }

    private static String randomPassword() {
        // 去掉容易看错的 0/O/1/l/I，反正这是给人抄一次的
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
