package cn.zzuit.campus.identity;

import java.util.List;
import java.util.Optional;
import cn.zzuit.campus.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class IdentityService implements UserDetailsService {
    private final JdbcClient jdbc;
    public IdentityService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.sql("SELECT * FROM app_user WHERE username=:username").param("username", username)
            .query(this::principal).optional().orElseThrow(() -> new UsernameNotFoundException("Account unavailable"));
    }

    private CampusPrincipal principal(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        long id=rs.getLong("id");
        return new CampusPrincipal(id,rs.getString("username"),rs.getString("password_hash"),rs.getBoolean("enabled"),roles(id),permissions(id),rs.getLong("security_version"));
    }
    public Optional<CampusPrincipal> current(long id) {
        return jdbc.sql("SELECT * FROM app_user WHERE id=:u AND enabled=TRUE").param("u",id).query(this::principal).optional();
    }
    public void requirePermission(CampusPrincipal user,String permission) {
        var current=current(user.id()).orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","账号已停用，请重新登录"));
        if (current.securityVersion()!=user.securityVersion()) throw new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","账号权限或归属已变更，请重新登录");
        if (passwordChangeRequired(user.id())) throw new ApiException(HttpStatus.FORBIDDEN,"PASSWORD_CHANGE_REQUIRED","请先在个人资料中修改初始密码");
        if (!current.permissions().contains(permission)) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","当前岗位没有此操作权限");
    }

    public List<String> roles(long id) {
        return jdbc.sql("SELECT role_code FROM user_role WHERE user_id=:id ORDER BY role_code")
            .param("id", id).query(String.class).list();
    }

    public boolean passwordChangeRequired(long id) {
        return jdbc.sql("SELECT must_change_password FROM app_user WHERE id=:id").param("id",id).query(Boolean.class).single();
    }

    public List<String> permissions(long id) {
        return jdbc.sql("""
            SELECT DISTINCT rp.permission_code FROM user_role ur
            JOIN role_permission rp ON rp.role_code=ur.role_code
            WHERE ur.user_id=:id ORDER BY rp.permission_code
            """).param("id", id).query(String.class).list();
    }

    public Profile profile(long id) {
        return jdbc.sql("""
            SELECT u.id,u.username,u.full_name,u.identity_number,u.demo,u.avatar_url,u.must_change_password,
                   c.name campus_name,d.name department_name,cg.name class_name
            FROM app_user u
            LEFT JOIN campus c ON c.id=u.campus_id
            LEFT JOIN department d ON d.id=u.department_id
            LEFT JOIN class_group cg ON cg.id=u.class_group_id
            WHERE u.id=:id AND u.enabled=TRUE
            """).param("id", id).query((rs, row) -> new Profile(rs.getLong("id"),
                rs.getString("username"), rs.getString("full_name"), rs.getString("identity_number"),
                rs.getString("campus_name"), rs.getString("department_name"), rs.getString("class_name"),
                rs.getString("avatar_url"), rs.getBoolean("demo"), roles(id), permissions(id),rs.getBoolean("must_change_password"))).single();
    }

    public Profile updateAvatar(long userId, String avatarUrl) {
        jdbc.sql("UPDATE app_user SET avatar_url=:avatarUrl WHERE id=:id AND enabled=TRUE")
            .param("avatarUrl", avatarUrl).param("id", userId).update();
        return profile(userId);
    }

    public Profile removeAvatar(long userId) {
        jdbc.sql("UPDATE app_user SET avatar_url=NULL WHERE id=:id AND enabled=TRUE")
            .param("id", userId).update();
        return profile(userId);
    }

    public record Profile(long id, String username, String fullName, String identityNumber,
                          String campusName, String departmentName, String className,
                          String avatarUrl, boolean demo, List<String> roles, List<String> permissions,boolean passwordChangeRequired) {}
}
