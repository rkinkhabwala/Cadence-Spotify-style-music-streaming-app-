/**
 * Shared kernel: error handling, pagination, outbox, messaging and web plumbing used by every bounded context.
 * Declared OPEN so contexts may use its sub-packages; it must never depend on a bounded context.
 */
@ApplicationModule(displayName = "Common", type = ApplicationModule.Type.OPEN)
package com.cadence.common;

import org.springframework.modulith.ApplicationModule;
