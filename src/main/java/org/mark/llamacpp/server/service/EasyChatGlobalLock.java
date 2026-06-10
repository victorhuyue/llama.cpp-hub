package org.mark.llamacpp.server.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class EasyChatGlobalLock {

	private static final EasyChatGlobalLock INSTANCE = new EasyChatGlobalLock();

	public static EasyChatGlobalLock getInstance() {
		return INSTANCE;
	}

	private final AtomicReference<LockState> stateRef = new AtomicReference<>();

	private EasyChatGlobalLock() {
	}

	public Lease tryAcquire(String operationName) {
		String normalizedOperation = operationName == null || operationName.isBlank()
			? "easy-chat-operation"
			: operationName.trim();
		LockState nextState = new LockState(normalizedOperation, System.currentTimeMillis());
		if (!stateRef.compareAndSet(null, nextState)) {
			return null;
		}
		return new Lease(this, nextState);
	}

	public LockState current() {
		return stateRef.get();
	}

	private void release(LockState state) {
		if (state == null) {
			return;
		}
		stateRef.compareAndSet(state, null);
	}

	public static final class Lease implements AutoCloseable {
		private final EasyChatGlobalLock owner;
		private final LockState state;
		private final AtomicBoolean closed = new AtomicBoolean(false);

		private Lease(EasyChatGlobalLock owner, LockState state) {
			this.owner = owner;
			this.state = state;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				owner.release(state);
			}
		}
	}

	public static final class LockState {
		private final String operationName;
		private final long startedAt;

		private LockState(String operationName, long startedAt) {
			this.operationName = operationName;
			this.startedAt = startedAt;
		}

		public String operationName() {
			return operationName;
		}

		public long startedAt() {
			return startedAt;
		}
	}
}
