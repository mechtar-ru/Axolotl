import React, { useState } from 'react';
import { Handle, Position } from 'reactflow';
import './NodeStyles.css';

interface OutputNodeProps {
  data: {
    name: string;
    result?: string;
    onRename?: (newName: string) => void;
  };
}

const OutputNode: React.FC<OutputNodeProps> = ({ data }) => {
  const [isEditingName, setIsEditingName] = useState(false);
  const [name, setName] = useState(data.name);
  
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
  
  return (
    <div className="node output-node">
      <Handle type="target" position={Position.Top} />
      <div className="node-header">
        <span className="node-icon">📤</span>
        {isEditingName ? (
          <input
            type="text"
            value={name}
            onChange={handleNameChange}
            onBlur={handleNameBlur}
            onKeyDown={(e) => e.key === 'Enter' && handleNameBlur()}
            autoFocus
            className="node-name-input"
          />
        ) : (
          <span className="node-name" onDoubleClick={handleNameDoubleClick}>
            {data.name}
          </span>
        )}
      </div>
      {data.result && (
        <div className="node-content">
          <div className="node-result">{data.result}</div>
        </div>
      )}
    </div>
  );
};

export default OutputNode;
