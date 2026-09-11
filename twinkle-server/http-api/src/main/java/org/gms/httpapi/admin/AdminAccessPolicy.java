package org.gms.httpapi.admin;
import io.micronaut.http.HttpMethod;
/** 权限规则的稳定调用契约；认证会话由宿主持有。 */
public interface AdminAccessPolicy {
    public Policy resolve(HttpMethod method, String path);
    public record Policy(boolean publicEndpoint, String requiredPermission) {
    }
}
