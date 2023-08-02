package com.flipkart.krystal.krystex.commands;

import com.flipkart.krystal.krystex.request.RequestId;
import java.util.Set;

public sealed interface BatchCommand extends NodeCommand permits ForwardBatch, CallbackBatch {

  Set<RequestId> requestIds();

  Set<String> inputNames();
}
