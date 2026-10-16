package com.jenishsoni.jiratriage.domain;

public record Change<T>(boolean dryRun, boolean changed, boolean applied, T before, T after) {
}
