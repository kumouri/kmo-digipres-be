package com.kumouri.kmodigipresbe.model.deal;

public enum PipelineStage {
    NEW, QUALIFIED, PROPOSAL, NEGOTIATION, WON, LOST;

    public boolean isTerminal() {
        return this == WON || this == LOST;
    }
}
