package cn.zzuit.campus.identity;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record CampusPrincipal(long id, String username, String password, boolean enabled,
                              List<String> roles, List<String> permissions, long securityVersion) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return java.util.stream.Stream.concat(
            roles.stream().map(role -> "ROLE_" + role), permissions.stream())
            .map(SimpleGrantedAuthority::new).toList();
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return enabled; }
}
