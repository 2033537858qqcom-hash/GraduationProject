package cn.zzuit.campus.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Refresh before URL/method authorization; never trust the session's permission snapshot. */
public class LiveIdentityFilter extends OncePerRequestFilter {
    private final IdentityService identities;
    private final SecurityContextRepository contexts;
    public LiveIdentityFilter(IdentityService identities,SecurityContextRepository contexts) { this.identities=identities; this.contexts=contexts; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication!=null && authentication.getPrincipal() instanceof CampusPrincipal prior) {
            var current=identities.current(prior.id());
            var context=SecurityContextHolder.createEmptyContext();
            if (current.isEmpty() || current.get().securityVersion()!=prior.securityVersion()) {
                var session=request.getSession(false);
                if (session!=null) session.invalidate();
                context.setAuthentication(new AnonymousAuthenticationToken("liveIdentity","anonymousUser",List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
                SecurityContextHolder.setContext(context);
            } else {
                var fresh=UsernamePasswordAuthenticationToken.authenticated(current.get(),null,current.get().getAuthorities());
                fresh.setDetails(authentication.getDetails()); context.setAuthentication(fresh);
                SecurityContextHolder.setContext(context); contexts.saveContext(context,request,response);
            }
        }
        chain.doFilter(request,response);
    }
}
