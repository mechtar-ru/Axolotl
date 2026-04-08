import React, { useCallback, useEffect, useState } from 'react';
import ReactFlow, {
  Node as FlowNode,
  Edge as FlowEdge,
  Connection as FlowConnection,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  addEdge,
  Panel,
  MarkerType,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { WorkflowSchema, Node as WorkflowNode, Edge as WorkflowEdge } from '../../types';
import AgentNode from '../NodeTypes/AgentNode';
import SourceNode from '../NodeTypes/SourceNode';
import OutputNode from '../NodeTypes/OutputNode';

interface WorkflowCanvasProps {
  schema: WorkflowSchema;
  onUpdate: (schema: WorkflowSchema) => void;
}

const nodeTypes = {
  agent: AgentNode,
  source: SourceNode,
  output: OutputNode,
};

const WorkflowCanvas: React.FC<WorkflowCanvasProps> = ({ schema, onUpdate }) => {
  const [nextNodeOffset, setNextNodeOffset] = useState(0);

  const generateUniqueName = (baseName: string, existingNodes: WorkflowNode[]): string => {
    const names = existingNodes.map(n => n.name);
    if (!names.includes(baseName)) return baseName;
    
    let counter = 1;
    while (names.includes(`${baseName} (${counter})`)) {
      counter++;
    }
    return `${baseName} (${counter})`;
  };

  const convertToFlowNodes = (nodes: WorkflowNode[]): FlowNode[] => {
    if (!nodes || !Array.isArray(nodes)) return [];
    return nodes.map(node => ({
      id: node.id,
      type: node.type,
      position: node.position || { x: 100, y: 100 },
      data: {
        name: node.name,
        status: node.status || 'idle',
        userPrompt: node.data?.userPrompt || '',
        sourceData: node.data?.sourceData || '',
        result: node.data?.result || '',
        onUpdate: (updates: any) => {
          const currentNodes = schema.nodes || [];
          const updatedNodes = currentNodes.map(n => {
            if (n.id === node.id) {
              return {
                ...n,
                ...updates,
                data: {
                  ...n.data,
                  ...updates,
                }
              };
            }
            return n;
          });
          onUpdate({ ...schema, nodes: updatedNodes });
        },
        onRename: (newName: string) => {
          const currentNodes = schema.nodes || [];
          const updatedNodes = currentNodes.map(n =>
            n.id === node.id ? { ...n, name: newName } : n
          );
          onUpdate({ ...schema, nodes: updatedNodes });
        },
      },
    }));
  };

  const convertToFlowEdges = (edges: WorkflowEdge[]): FlowEdge[] => {
    if (!edges || !Array.isArray(edges)) return [];
    return edges.map(edge => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      type: 'smoothstep',
      markerEnd: { type: MarkerType.ArrowClosed },
      style: { stroke: edge.type === 'control' ? '#ff6b6b' : '#4a9eff' },
    }));
  };

  const [nodes, setNodes, onNodesChange] = useNodesState(convertToFlowNodes(schema.nodes || []));
  const [edges, setEdges, onEdgesChange] = useEdgesState(convertToFlowEdges(schema.edges || []));

  useEffect(() => {
    setNodes(convertToFlowNodes(schema.nodes || []));
    setEdges(convertToFlowEdges(schema.edges || []));
  }, [schema.id, schema.nodes, schema.edges]);

  const onConnect = useCallback(
    (connection: FlowConnection) => {
      const newEdge: FlowEdge = {
        id: `edge-${Date.now()}`,
        source: connection.source || '',
        target: connection.target || '',
        type: 'smoothstep',
        markerEnd: { type: MarkerType.ArrowClosed },
      };
      setEdges((eds) => addEdge(newEdge, eds));
      
      const currentEdges = schema.edges || [];
      const updatedEdges: WorkflowEdge[] = [...currentEdges, {
        id: newEdge.id,
        source: connection.source || '',
        target: connection.target || '',
        type: 'data',
      }];
      onUpdate({ ...schema, edges: updatedEdges });
    },
    [setEdges, schema, onUpdate]
  );

  const onNodeDragStop = useCallback((_event: React.MouseEvent, node: FlowNode) => {
    const currentNodes = schema.nodes || [];
    const updatedNodes = currentNodes.map(n =>
      n.id === node.id ? { ...n, position: node.position } : n
    );
    onUpdate({ ...schema, nodes: updatedNodes });
  }, [schema, onUpdate]);

  const addNewNode = (type: string) => {
    const currentNodes = schema.nodes || [];
    const nameMap = {
      source: 'Входные данные',
      agent: 'Аналитик',
      output: 'Результат'
    };
    
    const baseName = nameMap[type as keyof typeof nameMap] || 'Новый узел';
    const uniqueName = generateUniqueName(baseName, currentNodes);
    
    const offset = nextNodeOffset;
    const newNode: WorkflowNode = {
      id: `node-${Date.now()}-${Math.random()}`,
      type: type as any,
      name: uniqueName,
      position: { x: 250 + offset * 30, y: 250 + offset * 30 },
      data: {},
      status: 'idle',
    };
    
    // Добавляем специфичные поля для типа узла
    if (type === 'agent') {
      newNode.data.userPrompt = '';
    }
    if (type === 'source') {
      newNode.data.sourceData = '';
    }
    
    setNextNodeOffset(offset + 1);
    const updatedNodes = [...currentNodes, newNode];
    onUpdate({ ...schema, nodes: updatedNodes });
  };

  const onSchemaNameChange = () => {
    const newName = prompt('Введите новое имя схемы:', schema.name);
    if (newName && newName.trim()) {
      onUpdate({ ...schema, name: newName.trim() });
    }
  };

  return (
    <div style={{ width: '100%', height: '100vh' }}>
      <div style={{ 
        position: 'absolute', 
        top: 10, 
        left: 10, 
        zIndex: 1000, 
        background: '#2d2d44', 
        padding: '8px 16px', 
        borderRadius: '8px',
        cursor: 'pointer'
      }} onClick={onSchemaNameChange}>
        📝 {schema.name}
      </div>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeDragStop={onNodeDragStop}
        nodeTypes={nodeTypes}
        fitView
      >
        <Background />
        <Controls />
        <Panel position="top-left" style={{ backgroundColor: '#1e1e2e', padding: '10px', borderRadius: '8px', marginTop: '40px' }}>
          <button onClick={() => addNewNode('source')} style={{ marginRight: '10px' }}>📥 Source</button>
          <button onClick={() => addNewNode('agent')} style={{ marginRight: '10px' }}>🤖 Agent</button>
          <button onClick={() => addNewNode('output')}>📤 Output</button>
        </Panel>
      </ReactFlow>
    </div>
  );
};

export default WorkflowCanvas;
