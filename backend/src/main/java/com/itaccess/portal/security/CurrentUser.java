package com.itaccess.portal.security;

/** Who is calling. Controllers receive it as a method parameter; see DemoHeaderUserResolver. */
public record CurrentUser(String username, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
