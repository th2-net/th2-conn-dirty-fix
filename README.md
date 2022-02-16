# th2-conn-dirty-fix

This microservice allows sending and receiving messages via FIX protocol

## Configuration

+ *autoStart* - enables/disable auto-starting of session on box start (`true` by default)
+ *autoStopAfter* - time in seconds after which session will be automatically stopped (`0` by default = disabled)
+ *totalThreads* - total amount of threads (IO threads included) used by the box (`2` by default)
+ *ioThreads* - amount of IO threads used by the box (`1` by default)
+ *maxBatchSize* - max size of outgoing message batch (`100` by default)
+ *maxFlushTime* - max message batch flush time (`1000` by default)
+ *reconnectDelay* - delay between reconnects (`5000` by default)
+ *publishSentEvents* - enables/disables publish of "message sent" events (`true` by default)
+ *sessions* - list of session settings

## Session settings

+ *sessionAlias* - session alias for incoming/outgoing th2 messages
+ *host* - service host
+ *port* - service port
+ *handler* - handler settings
+ *mangler* - mangler settings

## Handler settings

+ *beginString* - defines the start of a new message and the protocol version
+ *heartBtInt* - message waiting interval
+ *senderCompID* - ID of the sender of the message
+ *targetCompID* - ID of the message recipient
+ *encryptMethod* - encryption method
+ *username* - user name
+ *password* - user password
+ *testRequestDelay* - interval for test request
+ *reconnectDelay* - interval for reconnect
+ *disconnectRequestDelay* - the interval for the shutdown request
+ *resetSeqNumFlag* - resetting sequence number in initial Logon message (when conn started)
+ *resetOnLogon* - resetting the sequence number in Logon in other cases (e.g. disconnect)

## Mangler settings

Mangler is configured by specifying a list of transformations which it will try to apply to outgoing messages.   
Each transformation has a list of conditions which message must meet for transformation actions to be applied.

Condition is basically a field value check:

```yaml
tag: 35
matches: (8|D)
```

Where `tag` is a field tag to match and `matches` is a regex pattern for the field value.

Conditions are specified in `when` block of transformation definition:

```yaml
when:
  - tag: 35
    matches: (8|D)
  - tag: 49
    matches: SENDER(.*)
```

Actions describe modifications which will be applied to a message. There are 4 types of actions:

* set - sets value of an existing field to the specified value:

  ```yaml
  set:
    tag: 1
    value: new account
  ```

* add - adds new field before or after an existing field:

  ```yaml
  add:
    tag: 15
    value: USD
  after: # or before
    tag: 58
    matches: (.*)
  ```

* replace - replaces an existing field with another field:

  ```yaml
  replace:
    tag: 64
    matches: (.*)
  with:
    tag: 63
    value: 1
  ```

* remove - removes an existing field:

  ```yaml
  remove:
    tag: 110
    matches: (.*)
  ```

Actions are specified in `then` block of transformation definition:

```yaml
then:
  - set:
      tag: 1
      value: new account
  - remove:
      tag: 110
      matches: (.*)
```

Transformation can also automatically recalculate length and checksum if any actions were applied.  
This is controlled by `update-length` and `update-checksum` (both `true` by default) transformation options.

Full config will be divided into groups of transforms united by rules, each rule will have `id` as key and list of transforms. 
Only one rule can be triggered, after conditions tests triggered rules will be united into specific list and 
only one random rule (group of transforms) will be chosen.

```yaml
rules:
  - id: 1
    transform: [...]
  - id: 99
    transform: [...]
```

Complete mangler configuration would look something like this:

```yaml
mangler:
  rules: 
    - id: 1
      transform:
        - when:
            - tag: 8
              matches: FIXT.1.1
            - tag: 35
              matches: D
          then:
            - set:
                tag: 1
                value: new account
            - add:
                tag: 15
                value: USD
              after:
                tag: 58
                matches: (.*)
          update-length: false
        - when:
            - tag: 8
              matches: FIXT.1.1
            - tag: 35
              matches: 8
          then:
            - replace:
                tag: 64
                matches: (.*)
              with:
                tag: 63
                value: 1
            - remove:
                tag: 110
                matches: (.*)
          update-checksum: false
```
## MQ pins
+ input queue with `subscribe`, `send` and `raw` attributes for outgoing messages
+ output queue with `publish`, `first` (for incoming messages) or `second` (for outgoing messages) and `raw` attributes
## Deployment via infra-mgr
Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: fix-client
spec:
  image-name: ghcr.io/th2-net/th2-conn-dirty-fix
  image-version: 0.0.1
  type: th2-conn
  custom-config:
    autoStart: true
    autoStopAfter: 0
    totalThreads: 2
    ioThreads: 1
    maxBatchSize: 100
    maxFlushTime: 1000
    reconnectDelay: 5000
    publishSentEvents: true
    sessions:
      - sessionAlias: client
        secure: false
        host: "<host>"
        port: "<port>"
        handler:
          beginString: FIXT.1.1
          heartBtInt: 30
          senderCompID: client
          targetCompID: FGW
          encryptMethod: 0
          username: username
          password: password
          resetSeqNumFlag: false
          resetOnLogon: false
          testRequestDelay: 60
          reconnectDelay": 5
          disconnectRequestDelay: 5
        mangler:
          rules:
            - id: 1
              transform:
                - when:
                    - { tag: 8, matches: FIXT.1.1 }
                    - { tag: 35, matches: D }
                  then:
                    - set: { tag: 1, value: new account }
                    - add: { tag: 15, value: USD }
                      after: { tag: 58, matches: (.*) }
                  update-length: false
                - when:
                    - { tag: 8, matches: FIXT.1.1 }
                    - { tag: 35, matches: 8 }
                  then:
                    - replace: { tag: 64, matches: (.*) }
                      with: { tag: 63, value: 1 }
                    - remove: { tag: 110, matches: (.*) }
                  update-checksum: false
  pins:
    - name: to_send
      connection-type: mq
      attributes:
        - subscribe
        - send
        - raw
      settings:
        storageOnDemand: false
        queueLength: 1000
    - name: outgoing_messages
      connection-type: mq
      attributes:
        - second
        - publish
        - raw
    - name: incoming_messages
      connection-type: mq
      attributes:
        - first
        - publish
        - raw
  extended-settings:
    externalBox:
      enabled: false
    service:
      enabled: false
    resources:
      limits:
        memory: 200Mi
        cpu: 600m
      requests:
        memory: 100Mi
        cpu: 20m
```
