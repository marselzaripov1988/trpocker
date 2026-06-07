package com.truholdem.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.AppProperties;
import com.truholdem.config.api.ApiV1Config;
import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.dto.CashTableResponse;
import com.truholdem.dto.CreateCashTableRequest;
import com.truholdem.model.CashTable;
import com.truholdem.service.CashGameService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Admin control plane for cash (ring) tables: create a table config. Gated by {@code app.cash.enabled}.
 */
@RestController
@ApiV1Config
@RequestMapping("/admin/cash/tables")
@Tag(name = "Admin Cash Tables", description = "Cash-table configuration (ADMIN role required)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCashTableController {

    private final CashGameService cashGameService;
    private final AppProperties appProperties;

    public AdminCashTableController(CashGameService cashGameService, AppProperties appProperties) {
        this.cashGameService = cashGameService;
        this.appProperties = appProperties;
    }

    @PostMapping
    @Operation(summary = "Create a cash table")
    public ResponseEntity<CashTableResponse> create(@Valid @RequestBody CreateCashTableRequest request) {
        assertEnabled();
        CashTable table = cashGameService.createTable(request.name(), request.asset(), request.smallBlind(),
                request.bigBlind(), request.minBuyIn(), request.maxBuyIn(), request.maxSeats(),
                request.rakeBasisPoints(), request.rakeCap());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CashTableResponse(
                table.getId(), table.getName(), table.getAsset(), table.getSmallBlind(), table.getBigBlind(),
                table.getMinBuyIn(), table.getMaxBuyIn(), table.getMaxSeats(), table.getRakeBasisPoints(),
                table.getRakeCap(), 0, table.isActive()));
    }

    private void assertEnabled() {
        if (!appProperties.getCash().isEnabled()) {
            throw new ResourceNotFoundException("Cash tables are not enabled");
        }
    }
}
