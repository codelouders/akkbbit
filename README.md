# akkbbit
Provides Akka stream's sources, flows and sinks to connect to Rabbit. 

## Feautures: 
 - Automatically reconnects.
 - Producer flow provides send status feedback.
 - connection reusage (few akka stages can share connection and just simply use separate channels)
 
## In progress
 - Consumer
