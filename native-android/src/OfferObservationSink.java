package com.ridio.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional side channel for {@link OfferObservation}. Production leaves the
 * listener unset, so publish is a no-op and cannot change Rapido/Ola filters
 * or acceptance. Tests install a {@link Recorder}.
 */
public final class OfferObservationSink {

  public interface Listener {
    void onOfferObserved(OfferObservation offer);
  }

  private static volatile Listener listener;

  private OfferObservationSink() {}

  public static void setListener(Listener next) {
    listener = next;
  }

  public static void clear() {
    listener = null;
  }

  public static Recorder installRecorder() {
    Recorder recorder = new Recorder();
    listener = recorder;
    return recorder;
  }

  public static void publish(OfferObservation offer) {
    Listener current = listener;
    if (current == null || offer == null) return;
    try {
      current.onOfferObserved(offer);
    } catch (Throwable ignored) {
    }
  }

  /** Parse and publish. Swallows all errors so callers stay observational. */
  public static void publishFromText(
      String packageName,
      String text,
      String source,
      long observedAtMs) {
    try {
      publish(OfferObservation.fromText(packageName, text, source, observedAtMs));
    } catch (Throwable ignored) {
    }
  }

  public static final class Recorder implements Listener {
    private final List<OfferObservation> items = new ArrayList<>();

    @Override
    public synchronized void onOfferObserved(OfferObservation offer) {
      if (offer != null) items.add(offer);
    }

    public synchronized int size() {
      return items.size();
    }

    public synchronized OfferObservation last() {
      return items.isEmpty() ? null : items.get(items.size() - 1);
    }

    public synchronized OfferObservation get(int index) {
      return items.get(index);
    }

    public synchronized List<OfferObservation> snapshot() {
      return new ArrayList<>(items);
    }
  }
}
