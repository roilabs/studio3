package com.aptana.editor.js.parsing.ast;

public class JSDefaultNode extends JSNaryNode
{
	/**
	 * JSDefaultNode
	 * 
	 * @param start
	 * @param end
	 */
	public JSDefaultNode(JSNode... children)
	{
		super(JSNodeTypes.DEFAULT, children);
	}

	/*
	 * (non-Javadoc)
	 * @see com.aptana.editor.js.parsing.ast.JSNode#accept(com.aptana.editor.js.parsing.ast.JSTreeWalker)
	 */
	@Override
	public void accept(JSTreeWalker walker)
	{
		walker.visit(this);
	}
	
	/*
	 * (non-Javadoc)
	 * @see com.aptana.editor.js.parsing.ast.JSNaryNode#appendOpenText(java.lang.StringBuilder)
	 */
	@Override
	protected void appendOpenText(StringBuilder buffer)
	{
		buffer.append("default: "); //$NON-NLS-1$
	}

	/*
	 * (non-Javadoc)
	 * @see com.aptana.editor.js.parsing.ast.JSNaryNode#getDelimiter()
	 */
	@Override
	protected String getDelimiter()
	{
		return "";
	}
}
