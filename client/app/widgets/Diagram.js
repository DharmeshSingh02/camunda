import {jsx, updateOnlyWhenStateChanges, withSelector} from 'view-utils';
import Viewer from 'bpmn-js/lib/NavigatedViewer';
import {isLoaded} from 'utils/loading';
import {Loader} from './LoadingIndicator';

export function createDiagram() {
  const BpmnViewer = createBpmnViewer();
  const Diagram = withSelector(({createOverlaysRenderer}) => {
    return (node, eventsBus) => {
      const template = <div>
        <Loader className="diagram-loading" style="position: absolute" />
        <div className="diagram__holder">
          <BpmnViewer onLoaded={onLoaded} createOverlaysRenderer={createOverlaysRenderer} />
        </div>
      </div>;
      const templateUpdate = template(node, eventsBus);
      const loaderNode = node.querySelector('.diagram-loading');

      return templateUpdate;

      function onLoaded() {
        loaderNode.style.display = 'none';
      }
    };
  });

  Diagram.getViewer = BpmnViewer.getViewer;

  return Diagram;
}

function createBpmnViewer() {
  let viewer;

  const BpmnViewer = ({onLoaded, createOverlaysRenderer = []}) => {
    return (node, eventsBus) => {
      viewer = new Viewer({
        container: node
      });

      if (typeof createOverlaysRenderer === 'function') {
        createOverlaysRenderer = [createOverlaysRenderer];
      }

      const renderOverlays = createOverlaysRenderer.map(createFct => createFct({
        viewer,
        node,
        eventsBus
      }));
      let diagramRendered = false;

      const update = (display) => {
        if (isLoaded(display.diagram)) {
          renderDiagram(display);
        } else {
          diagramRendered = false;
        }

        renderOverlays.forEach(fct => fct({
          state: display,
          diagramRendered
        }));
      };

      function renderDiagram(display) {
        if (!diagramRendered) {
          viewer.importXML(display.diagram.data, (err) => {
            if (err) {
              node.innerHTML = `Could not load diagram, got error ${err}`;
            }
            diagramRendered = true;
            resetZoom(viewer);

            renderOverlays.forEach(fct => fct({
              state: display,
              diagramRendered
            }));

            onLoaded();
          });
        }
      }

      return updateOnlyWhenStateChanges(update);
    };
  };

  BpmnViewer.getViewer = () => {
    return viewer;
  };

  return BpmnViewer;
}

export function resetZoom(viewer) {
  const canvas = viewer.get('canvas');

  canvas.resized();
  canvas.zoom('fit-viewport', 'auto');
}
