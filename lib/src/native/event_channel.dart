import 'dart:async';

import 'package:flutter/services.dart';

import 'utils.dart';

class FlutterWebRTCEventChannel {
  FlutterWebRTCEventChannel._internal() {
    EventChannel('FlutterWebRTC.Event')
        .receiveBroadcastStream()
        .listen(eventListener, onError: errorListener);
  }

  static final FlutterWebRTCEventChannel instance =
      FlutterWebRTCEventChannel._internal();

  final StreamController<Map<String, dynamic>> handleEvents =
      StreamController.broadcast();

  void eventListener(dynamic event) async {
    forEachBatchedEvent(event, (map) {
      handleEvents.add(<String, dynamic>{map['event'] as String: map});
    });
  }

  void errorListener(Object obj) {
    if (obj is Exception) {
      throw obj;
    }
  }
}
