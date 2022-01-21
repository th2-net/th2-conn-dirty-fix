# th2-conn-dirty-fix
This microservice allows sending and receiving messages via FIX protocol
## Configuration
Configuration should be specified in the custom configuration block in schema editor.
Parameters:
+ *sessionAlias* - session alias for incoming/outgoing th2 messages
+ *host* - service host
+ *port* - service port
+ *handler*:
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
+ *mangler*
#MQ pins
+ input queue with `subscribe`, `send` and `raw` attributes for outgoing messages
+ output queue with `publish`, `first` (for incoming messages) or `second` (for outgoing messages) and `raw` attributes
#Deployment via infra-mgr
Here's an example of `infra-mgr` config required to deploy this service
```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: fix-client
spec:
  image-name: ghcr.io/th2-net/th2-conn-dirty-fix
  image-version: 0.0.1
  custom-config:
    "sessionAlias": "client",
    "secure": "false",
    "host": "<host>",
    "port": "<port>",
    "handler": {
      "beginString": "FIXT.1.1",
      "heartBtInt": "30",
      "senderCompID": "client",
      "targetCompID": "FGW",
      "encryptMethod": "0",
      "username": "username",
      "password": "password",
      "testRequestDelay": "60",
      "reconnectDelay": "5",
      "disconnectRequestDelay": "5"
    },
    "mangler": {}
  type: th2-conn
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