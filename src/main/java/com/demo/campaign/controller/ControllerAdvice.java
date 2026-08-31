package com.demo.campaign.controller;

import com.demo.campaign.dto.ErrorResponse;
import com.demo.campaign.service.exception.CampaignClaimedAlreadyException;
import com.demo.campaign.service.exception.CampaignNoAvailableSeatsException;
import com.demo.campaign.service.exception.ExistingCampaignCodeException;
import com.demo.campaign.service.exception.NoSuchCampaignException;
import com.demo.campaign.service.exception.NoSuchClaimCampaignException;
import com.demo.campaign.service.exception.NoSuchUserException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;

@RestControllerAdvice
@Slf4j
public class ControllerAdvice {

    @ExceptionHandler(CampaignNoAvailableSeatsException.class)
    public ResponseEntity<ErrorResponse> handle(CampaignNoAvailableSeatsException ex) {
        var error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(CampaignClaimedAlreadyException.class)
    public ResponseEntity<ErrorResponse> handle(CampaignClaimedAlreadyException ex) {
        var error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(NoSuchCampaignException.class)
    public ResponseEntity<ErrorResponse> handle(NoSuchCampaignException ex) {
        var error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(NoSuchClaimCampaignException.class)
    public ResponseEntity<ErrorResponse> handle(NoSuchClaimCampaignException ex) {
        var error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(NoSuchUserException.class)
    public ResponseEntity<ErrorResponse> handle(NoSuchUserException ex) {
        var error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ExistingCampaignCodeException.class)
    public ResponseEntity<ErrorResponse> handle(ExistingCampaignCodeException ex) {
        var error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handle(MethodArgumentNotValidException ex) {
        var errors = new HashMap<String, String>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));

        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        log.error("An unexpected error occurred on the server.", ex);
        var error = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "An unexpected error occurred on the server.");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
