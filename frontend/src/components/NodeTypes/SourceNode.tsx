import React, { useState, useCallback } from 'react';
import { Handle, Position } from 'reactflow';
import './NodeStyles.css';

interface SourceItem {
  id: string;
  type: 'file' | 'database' | 'text';
  name: string;
  content: string;
}

interface SourceNodeProps {
  data: {
    name: string;
    sources?: SourceItem[];
    onUpdate: (updates: any) => void;
    onRename?: (newName: string) => void;
  };
}

const SourceNode: React.FC<SourceNodeProps> = ({ data }) => {
  const [isEditingName, setIsEditingName] = useState(false);
  const [name, setName] = useState(data.name);
  const [showAddMenu, setShowAddMenu] = useState(false);
  const [editingSource, setEditingSource] = useState<SourceItem | null>(null);
  const [newSourceType, setNewSourceType] = useState<'file' | 'database' | 'text'>('text');
  
  const sources = data.sources || [];
  
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
  
  const addSource = (type: 'file' | 'database' | 'text') => {
    const newSource: SourceItem = {
      id: `source-${Date.now()}`,
      type,
      name: type === 'file' ? 'Новый файл' : type === 'database' ? 'Новое подключение' : 'Новый текст',
      content: ''
    };
    const updatedSources = [...sources, newSource];
    data.onUpdate({ sources: updatedSources });
    setShowAddMenu(false);
    setEditingSource(newSource);
  };
  
  const updateSource = (sourceId: string, updates: Partial<SourceItem>) => {
    const updatedSources = sources.map(s => 
      s.id === sourceId ? { ...s, ...updates } : s
    );
    data.onUpdate({ sources: updatedSources });
  };
  
  const deleteSource = (sourceId: string) => {
    const updatedSources = sources.filter(s => s.id !== sourceId);
    data.onUpdate({ sources: updatedSources });
    if (editingSource?.id === sourceId) {
      setEditingSource(null);
    }
  };
  
  const getSourceIcon = (type: string) => {
    switch (type) {
      case 'file': return '📁';
      case 'database': return '🗄️';
      default: return '📝';
    }
  };
  
  const getSourceTypeName = (type: string) => {
    switch (type) {
      case 'file': return 'Файл';
      case 'database': return 'База данных';
      default: return 'Текст';
    }
  };
  
  return (
    <div className="node source-node">
      <Handle type="target" position={Position.Top} />
      <div className="node-header">
        <span className="node-icon">📥</span>
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
      </div>
      <div className="node-content">
        <div className="source-add-btn">
          <button 
            className="source-btn-add"
            onClick={() => setShowAddMenu(!showAddMenu)}
            onMouseDown={(e) => e.stopPropagation()}
          >
            + Добавить источник
          </button>
        </div>
        
        {showAddMenu && (
          <div className="source-add-menu">
            <button onClick={() => addSource('text')} onMouseDown={(e) => e.stopPropagation()}>
              📝 Текст
            </button>
            <button onClick={() => addSource('file')} onMouseDown={(e) => e.stopPropagation()}>
              📁 Файл
            </button>
            <button onClick={() => addSource('database')} onMouseDown={(e) => e.stopPropagation()}>
              🗄️ База данных
            </button>
          </div>
        )}
        
        <div className="sources-list">
          {sources.map(source => (
            <div 
              key={source.id} 
              className={`source-item ${editingSource?.id === source.id ? 'editing' : ''}`}
              onClick={() => setEditingSource(source)}
              onMouseDown={(e) => e.stopPropagation()}
            >
              <div className="source-item-header">
                <span className="source-icon">{getSourceIcon(source.type)}</span>
                <input
                  type="text"
                  value={source.name}
                  onChange={(e) => updateSource(source.id, { name: e.target.value })}
                  onMouseDown={(e) => e.stopPropagation()}
                  onClick={(e) => e.stopPropagation()}
                  className="source-name-input"
                />
                <button 
                  className="source-delete"
                  onClick={() => deleteSource(source.id)}
                  onMouseDown={(e) => e.stopPropagation()}
                >
                  ✕
                </button>
              </div>
              
              {editingSource?.id === source.id && (
                <div className="source-edit-area">
                  {source.type === 'text' && (
                    <textarea
                      value={source.content}
                      onChange={(e) => updateSource(source.id, { content: e.target.value })}
                      onMouseDown={(e) => e.stopPropagation()}
                      onMouseUp={(e) => e.stopPropagation()}
                      placeholder="Введите текст..."
                      rows={4}
                    />
                  )}
                  {source.type === 'file' && (
                    <div className="source-file-upload">
                      <input 
                        type="file" 
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          if (file) {
                            const reader = new FileReader();
                            reader.onload = (event) => {
                              updateSource(source.id, { content: event.target?.result as string });
                            };
                            reader.readAsText(file);
                          }
                        }}
                        onMouseDown={(e) => e.stopPropagation()}
                      />
                      {source.content && <div className="file-preview">Файл загружен</div>}
                    </div>
                  )}
                  {source.type === 'database' && (
                    <div className="source-db-config">
                      <input 
                        type="text" 
                        placeholder="JDBC URL"
                        value={source.content.split('|')[0] || ''}
                        onChange={(e) => updateSource(source.id, { 
                          content: e.target.value + '|' + (source.content.split('|')[1] || '') 
                        })}
                        onMouseDown={(e) => e.stopPropagation()}
                      />
                      <input 
                        type="text" 
                        placeholder="Запрос"
                        value={source.content.split('|')[1] || ''}
                        onChange={(e) => updateSource(source.id, { 
                          content: (source.content.split('|')[0] || '') + '|' + e.target.value 
                        })}
                        onMouseDown={(e) => e.stopPropagation()}
                      />
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
};

export default SourceNode;
