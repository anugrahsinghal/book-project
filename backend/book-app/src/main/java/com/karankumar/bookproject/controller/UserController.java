/*
 * The book project lets a user keep track of different books they would like to read, are currently
 * reading, have read or did not finish.
 * Copyright (C) 2021  Karan Kumar
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.karankumar.bookproject.controller;

import com.karankumar.bookproject.dto.UserToDeleteDto;
import com.karankumar.bookproject.dto.UserToRegisterDto;
import com.karankumar.bookproject.model.account.User;
import com.karankumar.bookproject.service.EmailServiceImpl;
import com.karankumar.bookproject.service.UserAlreadyRegisteredException;
import com.karankumar.bookproject.service.UserService;
import com.karankumar.bookproject.constant.EmailConstant;
import com.karankumar.bookproject.template.EmailTemplate;
import com.karankumar.bookproject.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.mail.MessagingException;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@CrossOrigin(origins = "http://localhost:3000")
@RequestMapping(Mappings.USER)
public class UserController {
    public static final String INCORRECT_PASSWORD_ERROR_MESSAGE =
            "The current password entered is incorrect";

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final EmailServiceImpl emailService;

    private static final String USER_NOT_FOUND_ERROR_MESSAGE = "Could not find the user with ID %d";
    private static final String CURRENT_USER_NOT_FOUND_ERROR_MESSAGE = "Could not determine the current user";
    private static final String PASSWORD_WEAK_ERROR_MESSAGE = "Password is too weak";

    private final Environment environment;

    @Autowired
    public UserController(UserService userService, PasswordEncoder passwordEncoder,
                          EmailServiceImpl emailService, Environment environment) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.environment = environment;
    }

    @GetMapping("/users")
    public List<User> getAllUsers() {
        return userService.findAll();
    }

    @GetMapping("/user/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.findUserById(id)
                          .orElseThrow(() ->
                                  new ResponseStatusException(HttpStatus.NOT_FOUND,
                                  String.format(USER_NOT_FOUND_ERROR_MESSAGE, id))
                          );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<Object> register(@RequestBody UserToRegisterDto user) {
        try {
            userService.register(user);

            String activeProfile = this.environment.getActiveProfiles()[0];
            if (!activeProfile.equals("dev")) {
                emailService.sendMessageUsingThymeleafTemplate(
                        user.getUsername(),
                        EmailConstant.ACCOUNT_CREATED_SUBJECT,
                        EmailTemplate.getAccountCreatedEmailTemplate(
                                emailService.getUsernameFromEmail(user.getUsername())
                        )
                );
            }

            return ResponseEntity.status(HttpStatus.OK).body("User created");
        } catch (UserAlreadyRegisteredException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    "That email address is taken. If you already have an account, " +
                            "you can try logging in."
            );
        } catch (ConstraintViolationException ex) {
            Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
            List<String> errors = new ArrayList<>();
            for (ConstraintViolation<?> v : violations) {
                errors.add(v.getMessage());
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
        } catch (MessagingException e) {
            LOGGER.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @DeleteMapping()
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCurrentUser(@RequestBody UserToDeleteDto user) throws MessagingException {
        String password = user.getPassword();
        if (passwordEncoder.matches(password, userService.getCurrentUser().getPassword())) {
            User userEntity = userService.getCurrentUser();
            if (userEntity == null || userEntity.getId() == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
            }
            userService.deleteUserById(userEntity.getId());
            emailService.sendMessageUsingThymeleafTemplate(
                    userEntity.getEmail(),
                    EmailConstant.ACCOUNT_DELETED_SUBJECT,
                    EmailTemplate.getAccountDeletedEmailTemplate(
                            emailService.getUsernameFromEmail(userEntity.getEmail())
                    )
            );
        } else {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong password.");
        }
   }

    @PostMapping("/update-email")
    @ResponseStatus(HttpStatus.OK)
    public void updateEmail(@RequestParam("newEmail") String newEmail,
                            @RequestParam("currentPassword") String currentPassword) {

        User user;

        try {
            user = userService.getCurrentUser();
        } catch (com.karankumar.bookproject.backend.service.CurrentUserNotFoundException ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    CURRENT_USER_NOT_FOUND_ERROR_MESSAGE
            );
        }

        if (passwordEncoder.matches(currentPassword, user.getPassword())) {
            try {
                userService.changeUserEmail(user, newEmail);
            } catch (ConstraintViolationException | UserAlreadyRegisteredException ex) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        ex.getMessage()
                );
            }
        } else {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    INCORRECT_PASSWORD_ERROR_MESSAGE
            );
        }
    }

    @PostMapping("/update-password")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<String> updatePassword(
            @RequestParam("currentPassword") String currentPassword,
            @RequestParam("newPassword") String newPassword) throws MessagingException {

        // TODO: move to service
        if (!StringUtils.passwordStrengthIsVeryStrong(newPassword)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                 .body(PASSWORD_WEAK_ERROR_MESSAGE);
        }

        User user;

        try {
            user = userService.getCurrentUser();
        } catch (com.karankumar.bookproject.backend.service.CurrentUserNotFoundException ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    CURRENT_USER_NOT_FOUND_ERROR_MESSAGE
            );
        }

        if (passwordEncoder.matches(currentPassword, user.getPassword())) {
            try {
                userService.changeUserPassword(user, newPassword);
                emailService.sendMessageUsingThymeleafTemplate(
                        user.getEmail(),
                        EmailConstant.ACCOUNT_PASSWORD_CHANGED_SUBJECT,
                        EmailTemplate.getChangePasswordEmailTemplate(
                                emailService.getUsernameFromEmail(user.getEmail())
                        )
                );
            } catch (ConstraintViolationException ex) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        ex.getMessage()
                );
            }
            return ResponseEntity.status(HttpStatus.OK).body("Updated");
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                 .body(INCORRECT_PASSWORD_ERROR_MESSAGE);
        }
    }
}
