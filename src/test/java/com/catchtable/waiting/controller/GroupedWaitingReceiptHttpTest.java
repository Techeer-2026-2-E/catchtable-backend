package com.catchtable.waiting.controller;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Exercise the same public receipt, rollover, capacity and rollback contracts with batching enabled. */
@SpringBootTest(properties = "waiting.intake.batch.enabled=true")
@Import(WaitingReceiptHttpTest.TimeConfig.class)
class GroupedWaitingReceiptHttpTest extends WaitingReceiptHttpTest {}
