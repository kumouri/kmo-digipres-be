package com.kumouri.kmodigipresbe.module.styleconsult.model;

/**
 * T9 (Salon "StyleConsult AI") — where a {@link StyleAttributes} read came from. Mirrors the T8
 * {@code module.quoting.model.AttributeSource}: {@code VISION} when the attributes were read off the
 * inspiration photo by {@code AiVisionService.extract}, {@code MANUAL} when the prospect typed them
 * into the intake form. Manual wins where both are present (the QuoteNow merge precedent).
 */
public enum StyleAttributeSource {
    VISION,
    MANUAL
}
