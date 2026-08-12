package com.citydrop.backend.user;

public class UsernameTakenException extends RuntimeException {
    public UsernameTakenException() {
        super("This username is already taken");
    }
}
