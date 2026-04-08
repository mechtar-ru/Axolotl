import React, { useState, useCallback, useRef } from 'react';
import { Handle, Position } from 'reactflow';
import './NodeStyles.css';

interface AgentNodeProps {
  data: {
    name: string;
    userPrompt?: string;
    status?: string;
    result?: string;
    onUpdate: (updates: any) => void;
    onRename?: (newName: string) => void;
  };
}

const AgentNode: React.FC<AgentNodeProps> = ({ data }) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [isEditingName, setIsEditingName] = useState(false);
  const [prompt, setPrompt] = useState(data.userPrompt || '');
  const [name, setName] = useState(data.name);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  
  const getStatusColor = () => {
    switch (data.status) {
      case 'running': return '#ffa500';
      case 'completed': return '#00ff00';
      case 'failed': return '#ff0000';
      default: return '#888';
    }
  };
  
  const handleNameDoubleClick = () => {
    setIsEditingName(true);
  };
  
  const handleNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setName(e.target.value);
  };
  
  const handleNameBlur = () => {
    if (name.trim() && data.onRename) {
      data.onRename(name.trim());
    } else {
      setName(data.name);
    }
    setIsEditingName(false);
  };
  
  const handlePromptChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const newValue = e.target.value;
    setPrompt(newValue);
    data.onUpdate({ userPrompt: newValue });
  }, [data.onUpdate]);
  
  // Полностью останавливаем все события мыши на textarea
  const handleTextareaMouseDown = (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    // Фокус на textarea после остановки события
    setTimeout(() => {
      textareaRef.current?.focus();
    }, 0);
  };
  
  const handleTextareaMouseUp = (e: React.MouseEvent) => {
    e.stopPropagation();
  };
  
  const handleTextareaClick = (e: React.MouseEvent) => {
    e.stopPropagation();
  };
  
  const handleExpandClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsExpanded(!isExpanded);
  };
  
  return (
    <div className="node agent-node" style={{ borderColor: getStatusColor() }}>
      <Handle type="target" position={Position.Top} />
      <div className="node-header">
        <span className="node-icon">🤖</span>
        {isEditingName ? (
          <input
            type="text"
            value={name}
            onChange={handleNameChange}
            onBlur={handleNameBlur}
            onKeyDown={(e) => e.key === 'Enter' && handleNameBlur()}
            onMouseDown={(e) => e.stopPropagation()}
            autoFocus
            className="node-name-input"
          />
        ) : (
          <span className="node-name" onDoubleClick={handleNameDoubleClick}>
            {data.name}
          </span>
        )}
        <span className="node-status" style={{ background: getStatusColor() }} />
        <button 
          className="node-expand" 
          onClick={handleExpandClick}
          onMouseDown={(e) => e.stopPropagation()}
        >
          {isExpanded ? '▼' : '▶'}
        </button>
      </div>
      
      {isExpanded && (
        <div className="node-content">
          <textarea
            ref={textareaRef}
            value={prompt}
            onChange={handlePromptChange}
            onMouseDown={handleTextareaMouseDown}
            onMouseUp={handleTextareaMouseUp}
            onClick={handleTextareaClick}
            placeholder="Введите промпт для агента..."
            rows={5}
            style={{ 
              width: '100%', 
              resize: 'vertical',
              userSelect: 'text',
              cursor: 'text'
            }}
          />
          {data.result && (
            <div className="node-result">
              <strong>Результат:</strong>
              <div>{data.result}</div>
            </div>
          )}
        </div>
      )}
      
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
};

export default AgentNode;
