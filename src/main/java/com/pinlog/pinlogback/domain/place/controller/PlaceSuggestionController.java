package com.pinlog.pinlogback.domain.place.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pinlog.pinlogback.domain.place.dto.PlaceSuggestionResponse;
import com.pinlog.pinlogback.domain.place.service.PlaceSuggestionService;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

@RestController
@RequestMapping("/v1/places")
public class PlaceSuggestionController {

	private final PlaceSuggestionService placeSuggestionService;

	public PlaceSuggestionController(PlaceSuggestionService placeSuggestionService) {
		this.placeSuggestionService = placeSuggestionService;
	}

	@PostMapping(value = "/suggestions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public PlaceSuggestionResponse suggest(@LoginMember MemberPrincipal me,
		@RequestPart(name = "image", required = false) List<MultipartFile> images) {
		return placeSuggestionService.suggest(images);
	}
}
