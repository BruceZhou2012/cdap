
function Ctrl (Redux, MyDagStore, jsPlumb, MyDAGFactory, $timeout) {
  MyDagStore.subscribe(() => {
    this.nodes = MyDagStore.getState().nodes;
    $timeout(render.bind(this));
  });
  var sourceSettings = angular.copy(MyDAGFactory.getSettings(false).source);
  var sinkSettings = angular.copy(MyDAGFactory.getSettings(false).sink);
  var transformSourceSettings = angular.copy(MyDAGFactory.getSettings(false).transformSource);
  var transformSinkSettings = angular.copy(MyDAGFactory.getSettings(false).transformSink);

  jsPlumb.ready(() => {
    var dagSettings = MyDAGFactory.getSettings().default;

    jsPlumb.setContainer('dag-container');
    this.instance = jsPlumb.getInstance(dagSettings);
    this.instance.bind('connection', () => {
      let conn = this.instance.getConnections().map( conn=> {
        return {from: conn.sourceId, to: conn.targetId};
      });
      MyDagStore.dispatch({
        type: 'SET-CONNECTION',
        connections: conn
      });
    });
    this.instance.bind('connectionDetached', () => {
      let conn = this.instance.getConnections().map( conn=> {
        return {from: conn.sourceId, to: conn.targetId};
      });
      MyDagStore.dispatch({
        type: 'SET-CONNECTION',
        connections: conn
      });
    });

  });

  let render = () => {
    angular.forEach(this.nodes,  (node) => {
      switch(node.endpoint) {
        case 'R':
          this.instance.addEndpoint(node.id, sourceSettings, {uuid: node.id});
          break;
        case 'L':
          this.instance.addEndpoint(node.id, sinkSettings, {uuid: node.id});
          break;
        case 'LR':
          // Need to id each end point so that it can be used later to make connections.
          this.instance.addEndpoint(node.id, transformSourceSettings, {uuid: 'Left' + node.id});
          this.instance.addEndpoint(node.id, transformSinkSettings, {uuid: 'Right' + node.id});
          break;
      }
    });
    var nodes = document.querySelectorAll('.box');
    this.instance.draggable(nodes, {
      start:  () => {},
      stop: () => {}
    });
  };
}

Ctrl.$inject = ['Redux', 'MyDagStore', 'jsPlumb', 'MyDAGFactory', '$timeout'];
angular.module(PKG.name + '.commons')
  .controller('MyDag1Ctrl', Ctrl);