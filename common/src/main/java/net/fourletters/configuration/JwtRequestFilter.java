package net.fourletters.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.fourletters.token.JwtTokenVerifier;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class JwtRequestFilter extends OncePerRequestFilter {

    private final JwtTokenVerifier jwtTokenVerifier;

    public JwtRequestFilter(JwtTokenVerifier jwtTokenVerifier) {
        this.jwtTokenVerifier = jwtTokenVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {

        String jwtToken = JwtTokenVerifier.getTokenFromBearerString(request.getHeader("Authorization"));
        if (jwtToken == null) {
            jwtToken = request.getParameter("token");
        }
        if (jwtToken != null) {
            var maybeClaims = jwtTokenVerifier.parseClaims(jwtToken);
            if (maybeClaims.isPresent()) {
                var claims = maybeClaims.get();
                String username = claims.getSubject();
                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    List<String> roles = JwtTokenVerifier.getRolesFromClaims(claims);
                    List<GrantedAuthority> authorities = roles.stream()
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList());
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            username, null, authorities);
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        }
        // if not authentication set should fail (if not configured in WebSecurityConfig)
        chain.doFilter(request, response);
    }
}
