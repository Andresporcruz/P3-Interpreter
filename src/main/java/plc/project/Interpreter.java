//Andres Portillo
//P3: Interpreter
//Last modified Oct 23, 2024


package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * The Interpreter class implements the visitor pattern for evaluating an abstract syntax tree (AST)
 * generated from the PLC (programming language compiler) source code. The evaluation executes the
 * code represented by the AST.
 */
public class Interpreter implements Ast.Visitor<Environment.PlcObject> {

    private Scope scope; // Stores the current scope for variable and function definitions.

    /**
     * Constructor initializes the interpreter with a parent scope and defines the "print" function.
     * @param parent The parent scope (usually global scope).
     */
    public Interpreter(Scope parent) {
        scope = new Scope(parent);
        // Define the "print" function, which outputs to the console and returns NIL.
        scope.defineFunction("print", 1, args -> {
            System.out.println(args.get(0).getValue());
            return Environment.NIL;
        });
    }

    public Scope getScope() {
        return scope;
    }

    /**
     * Visits the source node (root of the AST), evaluates fields and methods,
     * and invokes the "main" function.
     */
    @Override
    public Environment.PlcObject visit(Ast.Source ast) {
        // Process field declarations.
        for (Ast.Field field : ast.getFields()) {
            visit(field);
        }
        // Process method declarations.
        for (Ast.Method method : ast.getMethods()) {
            visit(method);
        }
        // Lookup and invoke the main function.
        Environment.Function mainFunction = scope.lookupFunction("main", 0);
        if (mainFunction == null) {
            throw new RuntimeException("Main function not defined");
        }
        return mainFunction.invoke(Collections.emptyList()); // Invoke main without arguments.
    }

    /**
     * Visits a field declaration, initializes it if a value is provided.
     */
    @Override
    public Environment.PlcObject visit(Ast.Field ast) {
        // Evaluate the field value if provided, otherwise use NIL.
        Environment.PlcObject value = ast.getValue().isPresent()
                ? visit(ast.getValue().get())
                : Environment.NIL;
        // Define the field in the current scope.
        scope.defineVariable(ast.getName(), value);
        return Environment.NIL;
    }

    /**
     * Visits a method declaration and defines it in the current scope.
     * When invoked, the method will create a new scope and execute its statements.
     */
    @Override
    public Environment.PlcObject visit(Ast.Method ast) {
        scope.defineFunction(ast.getName(), ast.getParameters().size(), args -> {
            Scope previousScope = scope; // Save the previous scope.
            try {
                scope = new Scope(scope); // Create a new scope for the method.
                // Define the method's parameters in the new scope.
                for (int i = 0; i < ast.getParameters().size(); i++) {
                    scope.defineVariable(ast.getParameters().get(i), args.get(i));
                }
                // Execute the method's statements.
                try {
                    for (Ast.Stmt statement : ast.getStatements()) {
                        visit(statement);
                    }
                } catch (Return returnValue) {
                    // Catch and return the value if a return statement is encountered.
                    return returnValue.value;
                }
                return Environment.NIL; // If no return statement, return NIL.
            } finally {
                scope = previousScope; // Restore the previous scope after method execution.
            }
        });
        return Environment.NIL;
    }

    /**
     * Visits an expression statement and evaluates its expression.
     */
    @Override
    public Environment.PlcObject visit(Ast.Stmt.Expression ast) {
        visit(ast.getExpression()); // Simply evaluate the expression.
        return Environment.NIL;
    }

    /**
     * Visits a variable declaration, initializes the variable with the provided value, or NIL if absent.
     */
    @Override
    public Environment.PlcObject visit(Ast.Stmt.Declaration ast) {
        // Evaluate the value of the variable, or set it to NIL if no value is provided.
        Environment.PlcObject value = ast.getValue().isPresent()
                ? visit(ast.getValue().get())
                : Environment.NIL;
        // Define the variable in the current scope.
        scope.defineVariable(ast.getName(), value);
        return Environment.NIL;
    }

    /**
     * Visits an assignment statement and assigns a value to the target variable or field.
     */
    @Override
    public Environment.PlcObject visit(Ast.Stmt.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expr.Access)) {
            throw new RuntimeException("Invalid assignment target");
        }
        Ast.Expr.Access receiver = (Ast.Expr.Access) ast.getReceiver();
        Environment.PlcObject value = visit(ast.getValue()); // Evaluate the value being assigned.

        // Check if the receiver is a field (in an object) or a variable (in the current scope).
        if (receiver.getReceiver().isPresent()) {
            Environment.PlcObject object = visit(receiver.getReceiver().get());
            object.setField(receiver.getName(), value); // Assign to a field in the object.
        } else {
            scope.lookupVariable(receiver.getName()).setValue(value); // Assign to a variable in the scope.
        }
        return Environment.NIL;
    }

    /**
     * Visits an if statement, evaluates the condition, and executes the appropriate block.
     */
    @Override
    public Environment.PlcObject visit(Ast.Stmt.If ast) {
        Boolean condition = requireType(Boolean.class, visit(ast.getCondition())); // Evaluate the condition.
        Scope previousScope = scope; // Save the current scope.
        if (condition) {
            scope = new Scope(scope); // Create a new scope for the "then" block.
            try {
                for (Ast.Stmt stmt : ast.getThenStatements()) {
                    visit(stmt); // Execute the "then" block.
                }
            } finally {
                scope = previousScope; // Restore the previous scope.
            }
        } else {
            scope = new Scope(scope); // Create a new scope for the "else" block.
            try {
                for (Ast.Stmt stmt : ast.getElseStatements()) {
                    visit(stmt); // Execute the "else" block.
                }
            } finally {
                scope = previousScope; // Restore the previous scope.
            }
        }
        return Environment.NIL;
    }

    /**
     * Visits a for loop statement, iterating over a collection and executing the body for each element.
     */
    @Override
    public Environment.PlcObject visit(Ast.Stmt.For ast) {
        Environment.PlcObject iterable = visit(ast.getValue()); // Evaluate the iterable value.
        Iterable<?> elements = requireType(Iterable.class, iterable); // Ensure it's iterable.

        // Iterate over each element in the collection.
        for (Object element : elements) {
            Scope previousScope = scope;
            scope = new Scope(scope); // Create a new scope for each iteration.
            try {
                scope.defineVariable(ast.getName(), Environment.create(element)); // Define the loop variable.
                for (Ast.Stmt stmt : ast.getStatements()) {
                    visit(stmt); // Execute the loop body.
                }
            } finally {
                scope = previousScope; // Restore the previous scope after each iteration.
            }
        }
        return Environment.NIL;
    }

    /**
     * Visits a while loop, repeatedly evaluating the condition and executing the body until the condition is false.
     */
    @Override
    public Environment.PlcObject visit(Ast.Stmt.While ast) {
        while (requireType(Boolean.class, visit(ast.getCondition()))) { // Check if the condition is true.
            Scope previousScope = scope;
            scope = new Scope(scope); // Create a new scope for the loop body.
            try {
                for (Ast.Stmt stmt : ast.getStatements()) {
                    visit(stmt); // Execute the loop body.
                }
            } finally {
                scope = previousScope; // Restore the previous scope after each iteration.
            }
        }
        return Environment.NIL;
    }

    /**
     * Visits a return statement and throws a Return exception to break out of the method.
     */
    @Override
    public Environment.PlcObject visit(Ast.Stmt.Return ast) {
        throw new Return(visit(ast.getValue())); // Return the value by throwing a Return exception.
    }

    /**
     * Visits a literal expression, returning its value.
     */
    @Override
    public Environment.PlcObject visit(Ast.Expr.Literal ast) {
        if (ast.getLiteral() == null) {
            return Environment.NIL; // Return NIL for null literals.
        }
        return Environment.create(ast.getLiteral()); // Return the literal value.
    }

    /**
     * Visits a group expression (i.e., parentheses), simply returning the result of evaluating the inner expression.
     */
    @Override
    public Environment.PlcObject visit(Ast.Expr.Group ast) {
        return visit(ast.getExpression()); // Return the evaluated expression inside the group.
    }

    /**
     * Visits a binary expression, performing the corresponding operation (arithmetic, comparison, or logical).
     */
    @Override
    public Environment.PlcObject visit(Ast.Expr.Binary ast) {
        String operator = ast.getOperator();
        Environment.PlcObject left = visit(ast.getLeft()); // Evaluate the left operand.
        Environment.PlcObject right; // Declare the right operand.

        // Handle different binary operators.
        switch (operator) {
            case "AND":
            case "&&":
                Boolean leftBool = requireType(Boolean.class, left);
                if (!leftBool) {
                    return Environment.create(false); // Short-circuit evaluation for AND.
                }
                Boolean rightBool = requireType(Boolean.class, visit(ast.getRight()));
                return Environment.create(rightBool);
            case "OR":
            case "||":
                leftBool = requireType(Boolean.class, left);
                if (leftBool) {
                    return Environment.create(true); // Short-circuit evaluation for OR.
                }
                rightBool = requireType(Boolean.class, visit(ast.getRight()));
                return Environment.create(rightBool);
            case "<":
            case "<=":
            case ">":
            case ">=":
                Comparable leftValue = requireType(Comparable.class, left);
                right = visit(ast.getRight());
                Comparable rightValue = requireType(leftValue.getClass(), right);
                int comparison = leftValue.compareTo(rightValue);
                switch (operator) {
                    case "<":
                        return Environment.create(comparison < 0);
                    case "<=":
                        return Environment.create(comparison <= 0);
                    case ">":
                        return Environment.create(comparison > 0);
                    case ">=":
                        return Environment.create(comparison >= 0);
                }
                break;
            case "==":
            case "!=":
                right = visit(ast.getRight());
                if (operator.equals("==")) {
                    return Environment.create(Objects.equals(left.getValue(), right.getValue()));
                } else {
                    return Environment.create(!Objects.equals(left.getValue(), right.getValue()));
                }
            case "+":
                right = visit(ast.getRight());
                if (left.getValue() instanceof String || right.getValue() instanceof String) {
                    return Environment.create(left.getValue().toString() + right.getValue().toString());
                }
                return Environment.create(requireType(BigInteger.class, left).add(
                        requireType(BigInteger.class, right)));
            case "-":
                right = visit(ast.getRight());
                return Environment.create(requireType(BigInteger.class, left).subtract(
                        requireType(BigInteger.class, right)));
            case "*":
                right = visit(ast.getRight());
                return Environment.create(requireType(BigInteger.class, left).multiply(
                        requireType(BigInteger.class, right)));
            case "/":
                BigDecimal leftDecimal = requireType(BigDecimal.class, left);
                right = visit(ast.getRight());
                BigDecimal rightDecimal = requireType(BigDecimal.class, right);
                if (rightDecimal.equals(BigDecimal.ZERO)) {
                    throw new RuntimeException("Division by zero");
                }
                return Environment.create(leftDecimal.divide(rightDecimal, RoundingMode.HALF_EVEN));
        }
        throw new RuntimeException("Unsupported operator: " + operator); // Catch unsupported operators.
    }

    /**
     * Visits a variable or field access expression and returns its value.
     */
    @Override
    public Environment.PlcObject visit(Ast.Expr.Access ast) {
        if (ast.getReceiver().isPresent()) {
            Environment.PlcObject receiver = visit(ast.getReceiver().get());
            Environment.Variable variable = receiver.getField(ast.getName());
            return variable.getValue(); // Access the field in the receiver object.
        } else {
            Environment.Variable variable = scope.lookupVariable(ast.getName());
            return variable.getValue(); // Access the variable in the current scope.
        }
    }

    /**
     * Visits a function call expression and invokes the function with the evaluated arguments.
     */
    @Override
    public Environment.PlcObject visit(Ast.Expr.Function ast) {
        List<Environment.PlcObject> args = ast.getArguments().stream()
                .map(this::visit)
                .collect(Collectors.toList());
        if (ast.getReceiver().isPresent()) {
            Environment.PlcObject receiver = visit(ast.getReceiver().get());
            return receiver.callMethod(ast.getName(), args); // Call a method on the receiver object.
        } else {
            Environment.Function function = scope.lookupFunction(ast.getName(), ast.getArguments().size());
            if (function == null) {
                throw new RuntimeException("Function not found");
            }
            return function.invoke(args); // Invoke the function.
        }
    }

    /**
     * Helper method to ensure that the value is of the expected type.
     * If the type does not match, throws a RuntimeException.
     */
    private <T> T requireType(Class<T> clazz, Environment.PlcObject object) {
        Object value = object.getValue();
        while (value instanceof Environment.PlcObject) {
            value = ((Environment.PlcObject) value).getValue();
        }
        if (!clazz.isInstance(value)) {
            throw new RuntimeException("Expected type " + clazz.getName() + " but got " + value.getClass().getName());
        }
        return clazz.cast(value);
    }

    /**
     * Custom exception to handle return statements in methods.
     */
    private static class Return extends RuntimeException {
        private final Environment.PlcObject value;

        public Return(Environment.PlcObject value) {
            this.value = value;
        }
    }
}
