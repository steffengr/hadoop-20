// This autogenerated skeleton file illustrates how to build a server.
// You should copy it to another filename to avoid overwriting it.

#include "ThriftHadoopDatanode.h"
#include <protocol/TBinaryProtocol.h>
#include <server/TSimpleServer.h>
#include <transport/TServerSocket.h>
#include <transport/TBufferTransports.h>

using namespace ::apache::thrift;
using namespace ::apache::thrift::protocol;
using namespace ::apache::thrift::transport;
using namespace ::apache::thrift::server;

using boost::shared_ptr;

class ThriftHadoopDatanodeHandler : virtual public ThriftHadoopDatanodeIf {
 public:
  ThriftHadoopDatanodeHandler() {
    // Your initialization goes here
  }

  void recoverBlock(ThdfsBlock& _return, const TDatanodeID& datanode, const ThdfsNamespaceId& namespaceId, const ThdfsBlock& block, const bool keepLength, const std::vector<TDatanodeID> & targets, const int64_t deadline) {
    // Your implementation goes here
    printf("recoverBlock\n");
  }

  void getBlockInfo(ThdfsBlock& _return, const TDatanodeID& datanode, const ThdfsNamespaceId& namespaceid, const ThdfsBlock& block) {
    // Your implementation goes here
    printf("getBlockInfo\n");
  }

  void copyBlock(const TDatanodeID& datanode, const ThdfsNamespaceId& srcNamespaceId, const ThdfsBlock& srcblock, const ThdfsNamespaceId& dstNamespaceId, const ThdfsBlock& destBlock, const TDatanodeID& target, const bool asynchronous) {
    // Your implementation goes here
    printf("copyBlock\n");
  }

  void getBlockPathInfo(ThdfsBlockPath& _return, const TDatanodeID& datanode, const ThdfsNamespaceId& namespaceId, const ThdfsBlock& block) {
    // Your implementation goes here
    printf("getBlockPathInfo\n");
  }

};

int main(int argc, char **argv) {
  int port = 9090;
  shared_ptr<ThriftHadoopDatanodeHandler> handler(new ThriftHadoopDatanodeHandler());
  shared_ptr<TProcessor> processor(new ThriftHadoopDatanodeProcessor(handler));
  shared_ptr<TServerTransport> serverTransport(new TServerSocket(port));
  shared_ptr<TTransportFactory> transportFactory(new TBufferedTransportFactory());
  shared_ptr<TProtocolFactory> protocolFactory(new TBinaryProtocolFactory());

  TSimpleServer server(processor, serverTransport, transportFactory, protocolFactory);
  server.serve();
  return 0;
}

