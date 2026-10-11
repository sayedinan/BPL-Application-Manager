package com.bpl.orderapp.admin.log;

/** Fired when someone starts or stops watching an application's log topic. */
public record LogViewerEvent(Long applicationId, boolean joined) {}
