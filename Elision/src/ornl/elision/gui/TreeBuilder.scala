/*======================================================================
 *       _ _     _
 *   ___| (_)___(_) ___  _ __
 *  / _ \ | / __| |/ _ \| '_ \
 * |  __/ | \__ \ | (_) | | | |
 *  \___|_|_|___/_|\___/|_| |_|
 * The Elision Term Rewriter
 * 
 * Copyright (c) 2012 by UT-Battelle, LLC.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * Collection of administrative costs for redistribution of the source code or
 * binary form is allowed. However, collection of a royalty or other fee in excess
 * of good faith amount for cost recovery for such redistribution is prohibited.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER, THE DOE, OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
======================================================================*/

package ornl.elision.gui

import collection.mutable.ArrayStack
import collection.mutable.HashMap

import scala.actors.Actor

/** A factory class used to contruct TreeSprites. */
class TreeBuilder extends Thread {
    /** Maintains a stack of id->NodeSprite tables used to obtain the local NodeSprite variables for a particular method scope during Elision's process. */
    val scopeStack = new ArrayStack[HashMap[String, NodeSprite]]
    
    /** The current id->NodeSprite table being used by Elision's current method scope for referencing local NodeSprites. */
    var curScope : HashMap[String, NodeSprite] = null
    
    /** The root NodeSprite for the tree currently being built. */
    var root : NodeSprite = null
    
    /** A reference to the NodeSprite current being used as the subroot for an Elision method. */
    var subroot : NodeSprite = null
    /** The current subroot's ID */
    var subrootID : String = null
    
    /** Maximum Eva tree depth. If this is < 0, then there is assumed to be no maximum depth. */
    var treeMaxDepth = -1
    
    /** If true, this flag makes the TreeBuilder skip all processing commands until it is given a finishTree command. */
    var fatalError = false
    
    /** A reference for the TreeBuilder's actor. All operations with the TreeBuilder should be done through this actor to ensure concurrency. */
    val tbActor = new TreeBuilderActor(this)
    
    /** Clears the TreeBuilder's members. */
    def clear : Unit = {
        root = null
        subroot = null
        curScope = null
        scopeStack.clear()
        fatalError = false
    }
    
    /** 
     * Clears the TreeBuilder and creates a new tree containing only a root node. 
     * A scope table is added to the stack with only "root" in it which maps to the root node. 
     * @param rootLabel     The label for the root node of the new tree. This node will automatically be a comment node.
     */
    def newTree(rootLabel : String) : Unit = {
//        System.err.println("\nMaking a new tree")
        
        clear
        root = new NodeSprite(rootLabel)
        root.properties = ""
        pushTable("root")
        curScope += ("root" -> root)
        curScope += ("subroot" -> root)
        setSubroot("root")
    }
    
    /**
     * Creates a TreeSprite from the TreeBuilder and then clears the TreeBuilder.
     * @return      A TreeSprite corresponding to the structure of NodeSprites in the TreeBuilder with root as its root NodeSprite.
     */
    def finishTree : TreeSprite = {
//        System.err.println("Finishing current tree")
        
        val treeSprite = new TreeSprite(0,0,root)
        clear
        treeSprite
    }
    
    /** 
     * Creates a new scope table and pushes it onto scopeStack. 
     * curScope is set to this new scope table. 
     * The new scope starts with one mapping: "subroot" -> the current subroot. 
     */
    def pushTable(args : Any) : Unit = {
        if(fatalError) return
        
//        printIndent("Pushing new table - " + args)
        
        curScope = new HashMap[String, NodeSprite]
        scopeStack.push(curScope)
        curScope += ("root" -> root)
        curScope += ("subroot" -> subroot)
    }
    
    /** 
     * Pops and discards the current scope from scopeStack. 
     * curScope is set to the scopeStack's new top. 
     */
    def popTable(args : Any) : Unit = {
        if(fatalError || scopeStack.size == 1) return
        
//        printIndent("Popping current table - " + args)
        
        // set the current subroot to the subroot currently in the table.
        subroot = curScope("subroot")
        // pop and discard the current scope.
        scopeStack.pop 
        // use whatever scope is on the top of the stack.
        curScope = scopeStack.top
    }
    
    /**
     * Sets the current subroot of the TreeBuilder.
     * @param id        The key ID for our desired NodeSprite in the current scope table.
     */
    def setSubroot(id : String) : Unit = {
        if(this.isMaxDepth || fatalError) return
    //    printIndent("Setting new subroot: " + id)
        var keepgoing = true
        while(keepgoing) {
            try {
                subroot = curScope(id)
                subrootID = id
                keepgoing = false
            } 
            catch {
                case _ => System.err.println("TreeBuilder.setSubroot error: key \"" + id + "\" does not exist in current scope table.")
                    keepgoing = attemptStackRecovery
            }
        }
    }
    
    /**
     * Adds a new comment and atom NodeSprite to the current subroot's children list.
     * If an id is given, the new NodeSprite will be mapped from that id in the current scope table.
     * @param id            Optional id that the new atom node will be mapped with in the current scope table. It won't be mapped if id == "".
     * @param comment       The String being used as the new comment node's label.
     * @param atom          The BasicAtom the new atom node is being constructed from 
     */
    def addToSubroot(id : String, comment : String, atom : ornl.elision.core.BasicAtom) : Unit = {
        if(this.isMaxDepth || fatalError) return
        
    //    printIndent("addToSubroot: " + id)
        
        val parent = subroot
        val node = createCommentNode(comment, parent)
        val node2 = createAtomNode(atom, node)
        if(id != "") curScope += (id -> node2)
    }
    
    
    /** 
     * Adds a new comment NodeSprite to the current subroot's children list. 
     * If an id is given, the new NodeSprite will be mapped from that id in the current scope table.
     * @param id            Optional id that the new node will be mapped with in the current scope table. It won't be mapped if id == "".
     * @param commentAtom   The String being used as the new node's label.
     */
    def addToSubroot(id : String, commentAtom : String) : Unit = {
        if(this.isMaxDepth || fatalError) return

    //    printIndent("addToSubroot: " + id)

        val parent = subroot
        val node = createCommentNode(commentAtom, parent)
        if(id != "") curScope += (id -> node)
    }
    
    /** 
     * Adds a new atom NodeSprite to the current subroot's children list. 
     * If an id is given, the new NodeSprite will be mapped from that id in the current scope table.
     * @param id            Optional id that the new node will be mapped with in the current scope table. It won't be mapped if id == "".
     * @param atom          The BasicAtom the new node is being constructed from.
     */
    def addToSubroot(id : String, atom : ornl.elision.core.BasicAtom) : Unit = {
        if(this.isMaxDepth || fatalError) return

    //    printIndent("addToSubroot: " + id)

        val parent = subroot
        val node = createAtomNode(atom, parent)
        if(id != "") curScope += (id -> node)
    }
    
    /**
     * Adds a new comment and atom NodeSprite to another NodeSprite's children list.
     * If an id is given, the new NodeSprite will be mapped from that id in the current scope table.
     * @param parentID      The id key for the parent node in the current scope table.
     * @param id            Optional id that the new atom node will be mapped with in the current scope table. It won't be mapped if id == "".
     * @param comment       The String being used as the new comment node's label.
     * @param atom          The BasicAtom the new atom node is being constructed from 
     */
    def addTo(parentID : String, id : String, comment : String, atom : ornl.elision.core.BasicAtom) : Unit = {
        if(this.isMaxDepth || fatalError) return

    //    printIndent("addTo: " + (parentID, id))

        var keepgoing = true
        while(keepgoing) {
            try {
                val parent = curScope(parentID)
                val node = createCommentNode(comment, parent)
                val node2 = createAtomNode(atom, node)
                if(id != "") curScope += (id -> node2)
                keepgoing = false
            } 
            catch {
                case _ => System.err.println("TreeBuilder.addTo error: key \"" + parentID + "\" does not exist in current scope table.")
                    keepgoing = attemptStackRecovery
            }
        }
    }
    
    
    /**
     * Adds a new comment NodeSprite to another NodeSprite's children list.
     * If an id is given, the new NodeSprite will be mapped from that id in the current scope table.
     * @param parentID      The id key for the parent node in the current scope table.
     * @param id            Optional id that the new node will be mapped with in the current scope table. It won't be mapped if id == "".
     * @param commentAtom   The String being used as the new node's label.
     */
    def addTo(parentID : String, id : String, commentAtom : String) : Unit = {
        if(this.isMaxDepth || fatalError) return

    //    printIndent("addTo: " + (parentID, id))

        var keepgoing = true
        while(keepgoing) {
            try {
                val parent = curScope(parentID)
                val node = createCommentNode(commentAtom, parent)
                if(id != "") curScope += (id -> node)
                keepgoing = false
            } 
            catch {
                case _ => System.err.println("TreeBuilder.addTo error: key \"" + parentID + "\" does not exist in current scope table.")
                    keepgoing = attemptStackRecovery
            }
        }
    }
    
    /**
     * Adds a new atom NodeSprite to another NodeSprite's children list.
     * If an id is given, the new NodeSprite will be mapped from that id in the current scope table.
     * @param parentID      The id key for the parent node in the current scope table.
     * @param id            Optional id that the new node will be mapped with in the current scope table. It won't be mapped if id == "".
     * @param atom          The BasicAtom the new node is being constructed from.
     */
    def addTo(parentID : String, id : String, atom : ornl.elision.core.BasicAtom) : Unit = {
        if(this.isMaxDepth || fatalError) return

    //    printIndent("addTo: " + (parentID, id))

        var keepgoing = true
        while(keepgoing) {
            try {
                val parent = curScope(parentID)
                val node = createAtomNode(atom, parent)
                if(id != "") curScope += (id -> node)
                keepgoing = false
            } 
            catch {
                case _ => System.err.println("TreeBuilder.addTo error: key \"" + parentID + "\" does not exist in current scope table.")
                    keepgoing = attemptStackRecovery
            }
        }
    }
    
    
    
    
    
    
    /** Helper method used to create a comment NodeSprite */
    private def createCommentNode(commentAtom : String, parent : NodeSprite) : NodeSprite = {
        val node = new NodeSprite(commentAtom, parent, true)
        parent.addChild(node)
        node
    }
    
    /** Helper method used to create an atom NodeSprite */
    private def createAtomNode(atom : ornl.elision.core.BasicAtom, parent : NodeSprite) : NodeSprite = {
        val node = new NodeSprite(atom.toParseString, parent, false)
        
        // Set the node's properties String with the atom's basic properties.
        // Later it might be a good idea to use matching to set the properties according to the type of BasicAtom used.
        node.properties = "Class: " + atom.getClass + "\n\n"
        node.properties = "Class: " + atom.getClass + "\n\n"
		node.properties += "Type: " + atom.theType + "\n\n"
		node.properties += "De Bruijn index: " + atom.deBruijnIndex + "\n\n"
		node.properties += "Depth: " + atom.depth + "\n\n"
		
		node.properties += "Is bindable: " + atom.isBindable + "\n\n"
		node.properties += "Is false: " + atom.isFalse + "\n\n"
		node.properties += "Is true: " + atom.isTrue + "\n\n"
		node.properties += "Is De Bruijn index: " + atom.isDeBruijnIndex + "\n\n"
		node.properties += "Is constant: " + atom.isConstant + "\n\n"
		node.properties += "Is term: " + atom.isTerm + "\n\n"
		
		try {
			node.properties += "constant pool: \n"
			for(i <- atom.constantPool.get) node.properties += "\t" + i + "\n"
		} catch {
			case _ => {}
		}
        
        if(parent != null) parent.addChild(node)
        node
    }
    
    /** Helper method checks to if we've reached our depth limit for tree building. */
    private def isMaxDepth : Boolean = {
        if(treeMaxDepth < 0) false
        else (scopeStack.size >= treeMaxDepth)
    }
    
    /** A handy deubgging helper method that prefixes a number of spaces to a message equal to the current size of the scopeStack. */
    private def printIndent(str : String) : Unit = {
        for(i <- 0 until scopeStack.size) {
            System.err.print(" ")
        }
        System.err.print(scopeStack.size + "")
        System.err.println(str)
    }
    
    /** A helper method that was used to try recover the TreeBuilder's scope if for some reason a popTable command was forgotten somewhere. 
    Now it just halts further tree construction until Elision is done processing its current input. */
    private def attemptStackRecovery : Boolean = {
        if(false && scopeStack.size > 1) {
            popTable("n/a")
            true
        }
        else {
            addTo("root", "", "Fatal error during TreeBuilder tree construction. \n\tI just don't know what went wrong!")
            System.err.println("Fatal error during TreeBuilder tree construction. \n\tI just don't know what went wrong!")
            fatalError = true
            false
        }
    }
    
    
    
    /** Starts a new thread in which the TreeBuilder will run in. */
	override def run : Unit = {
		tbActor.start
        
        while(true) {}
	}
    
}


/** An actor object for doing concurrent operations with a TreeBuilder. */
class TreeBuilderActor(val treeBuilder : TreeBuilder) extends Actor {

    def act() = {
		loop {
			receive {
                case ("Eva", cmd : String, args : Any) => 
                    // process a TreeBuilder command received from the Elision.
                    processTreeBuilderCommands(cmd, args)
                case cmd => System.err.println("Bad tree builder command: " + cmd)
            }
        }
    }
    
    /** Called by act when the actor receives a valid TreeBuilder command. Here we actually invoke the methods of the TreeBuilder corresponding to the commands that the actor receives. */
    def processTreeBuilderCommands(cmd :String, args : Any) : Unit = {
        cmd match {
            case "newTree" =>
                args match {
                    case label : String =>
                        treeBuilder.newTree(label)
                    case _ => System.err.println("TreeBuilder.newTree received incorrect arguments: " + args)
                }
            case "finishTree" => // FINISH HIM. FATALITY. KO!
                mainGUI.treeVisPanel.isLoading = true
                mainGUI.treeVisPanel.treeSprite = treeBuilder.finishTree
                
                // once the tree visualization is built, select its root node and center the camera on it.
                mainGUI.treeVisPanel.selectNode(mainGUI.treeVisPanel.treeSprite.root)
                mainGUI.treeVisPanel.camera.reset
                
                mainGUI.treeVisPanel.isLoading = false
            case "pushTable" => 
                treeBuilder.pushTable(args)
            case "popTable" => 
                treeBuilder.popTable(args)
            case "setSubroot" =>
                args match {
                    case id : String =>
                        treeBuilder.setSubroot(id)
                    case _ => System.err.println("TreeBuilder.setSubroot received incorrect arguments: " + args)
                }
            case "addToSubroot" =>
                args match {
                    case (id : String, comment : String, atom : ornl.elision.core.BasicAtom) =>
                        treeBuilder.addToSubroot(id, comment, atom)
                    case (id : String, commentAtom : String) =>
                        treeBuilder.addToSubroot(id, commentAtom)
                    case (id : String, atom : ornl.elision.core.BasicAtom) =>
                        treeBuilder.addToSubroot(id, atom)
                    case _ => System.err.println("TreeBuilder.addToSubroot received incorrect arguments: " + args)
                }
            case "addTo" =>
                args match {
                    case (parentID : String, id : String, comment : String, atom : ornl.elision.core.BasicAtom) =>
                        treeBuilder.addTo(parentID, id, comment, atom)
                    case (parentID : String, id : String, commentAtom : String) =>
                        treeBuilder.addTo(parentID, id, commentAtom)
                    case (parentID : String, id : String, atom : ornl.elision.core.BasicAtom) =>
                        treeBuilder.addTo(parentID, id, atom)
                    case _ => System.err.println("TreeBuilder.addTo received incorrect arguments: " + args)
                }
            case _ => System.err.println("GUIActor received bad TreeBuilder command: " + cmd)
        }
    }
}

