package demo.lavka.controller;

import demo.lavka.auth.Authenticated;
import demo.lavka.auth.CurrentUser;
import demo.lavka.entity.User;
import demo.lavka.model.UserResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    @GetMapping("/me")
    @Authenticated
    public UserResponse me(@CurrentUser User user) {
        return UserResponse.from(user);
    }

}
