package com.fa26se040.icss.security.security;

import java.util.UUID;

public record CurrentUser(UUID id, String email, String roleType) {}
