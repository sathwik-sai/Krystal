package com.flipkart.krystal.krystex.kryon;

import com.flipkart.krystal.krystex.logicdecoration.OutputLogicDecorator;
import lombok.Builder;

public record VajramExecutorConfig(
    KryonExecutorConfig kryonExecutorConfig,
    OutputLogicDecorator inputInjector) {

  @Builder(toBuilder = true)
  public VajramExecutorConfig {}
}
