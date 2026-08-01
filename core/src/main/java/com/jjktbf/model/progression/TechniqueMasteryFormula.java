package com.jjktbf.model.progression;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Parses and evaluates the restricted arithmetic used by CTM progressions. */
final class TechniqueMasteryFormula {

    static final int MAX_LENGTH = 1_000;
    static final int MAX_DEPTH = 64;

    private TechniqueMasteryFormula() {
    }

    static Expression parse(String source) {
        if (source == null) throw new FormulaException("Formula is required");
        if (source.length() > MAX_LENGTH) {
            throw new FormulaException("Formula exceeds " + MAX_LENGTH + " characters");
        }
        return new Parser(source).parse();
    }

    interface Expression {
        Rational evaluate(Rational ctm);
    }

    private record LiteralExpression(Rational value) implements Expression {
        @Override
        public Rational evaluate(Rational ctm) {
            return value;
        }
    }

    private record VariableExpression() implements Expression {
        @Override
        public Rational evaluate(Rational ctm) {
            return ctm;
        }
    }

    private record UnaryExpression(char operator, Expression operand) implements Expression {
        @Override
        public Rational evaluate(Rational ctm) {
            Rational value = operand.evaluate(ctm);
            return operator == '-' ? value.negate() : value;
        }
    }

    private record BinaryExpression(char operator, Expression left, Expression right)
        implements Expression {

        @Override
        public Rational evaluate(Rational ctm) {
            Rational leftValue = left.evaluate(ctm);
            Rational rightValue = right.evaluate(ctm);
            return switch (operator) {
                case '+' -> leftValue.add(rightValue);
                case '-' -> leftValue.subtract(rightValue);
                case '*' -> leftValue.multiply(rightValue);
                case '/' -> leftValue.divide(rightValue);
                case '%' -> leftValue.remainder(rightValue);
                default -> throw new FormulaException("Unsupported operator: " + operator);
            };
        }
    }

    private record FunctionExpression(String name, List<Expression> arguments)
        implements Expression {

        @Override
        public Rational evaluate(Rational ctm) {
            Rational first = arguments.get(0).evaluate(ctm);
            if ("min".equals(name)) {
                Rational second = arguments.get(1).evaluate(ctm);
                return first.compareTo(second) <= 0 ? first : second;
            }
            if ("max".equals(name)) {
                Rational second = arguments.get(1).evaluate(ctm);
                return first.compareTo(second) >= 0 ? first : second;
            }

            Rational minimum = arguments.get(1).evaluate(ctm);
            Rational maximum = arguments.get(2).evaluate(ctm);
            if (minimum.compareTo(maximum) > 0) {
                throw new FormulaException("clamp minimum exceeds maximum");
            }
            if (first.compareTo(minimum) < 0) return minimum;
            if (first.compareTo(maximum) > 0) return maximum;
            return first;
        }
    }

    static final class FormulaException extends IllegalArgumentException {
        FormulaException(String message) {
            super(message);
        }
    }

    /** Exact finite decimal value represented as a reduced fraction. */
    static record Rational(BigInteger numerator, BigInteger denominator)
        implements Comparable<Rational> {

        private static final int MAX_COMPONENT_BITS = 4_096;
        private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
        private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

        Rational {
            if (denominator.signum() == 0) throw new FormulaException("Division by zero");
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger divisor = numerator.gcd(denominator);
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
            if (numerator.bitLength() > MAX_COMPONENT_BITS
                || denominator.bitLength() > MAX_COMPONENT_BITS) {
                throw new FormulaException("Numeric overflow");
            }
        }

        static Rational of(int value) {
            return of(BigInteger.valueOf(value));
        }

        static Rational of(BigInteger value) {
            return new Rational(value, BigInteger.ONE);
        }

        Rational add(Rational other) {
            return new Rational(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
        }

        Rational subtract(Rational other) {
            return add(other.negate());
        }

        Rational multiply(Rational other) {
            return new Rational(
                numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        Rational divide(Rational other) {
            if (other.numerator.signum() == 0) throw new FormulaException("Division by zero");
            return new Rational(
                numerator.multiply(other.denominator), denominator.multiply(other.numerator));
        }

        Rational remainder(Rational other) {
            if (other.numerator.signum() == 0) throw new FormulaException("Modulo by zero");
            Rational quotient = divide(other);
            BigInteger truncated = quotient.numerator.divide(quotient.denominator);
            return subtract(other.multiply(of(truncated)));
        }

        Rational negate() {
            return new Rational(numerator.negate(), denominator);
        }

        int floorToInt() {
            BigInteger[] result = numerator.divideAndRemainder(denominator);
            BigInteger floor = result[0];
            if (numerator.signum() < 0 && result[1].signum() != 0) {
                floor = floor.subtract(BigInteger.ONE);
            }
            if (floor.compareTo(INT_MIN) < 0 || floor.compareTo(INT_MAX) > 0) {
                throw new FormulaException("Formula result is outside the Java int range");
            }
            return floor.intValue();
        }

        @Override
        public int compareTo(Rational other) {
            return numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(denominator));
        }
    }

    private static final class Parser {
        private final String source;
        private int position;
        private int depth;

        private Parser(String source) {
            this.source = source;
        }

        private Expression parse() {
            skipWhitespace();
            if (atEnd()) throw error("Formula is blank");
            Expression expression = parseAdditive();
            skipWhitespace();
            if (!atEnd()) throw error("Unexpected token '" + current() + "'");
            return expression;
        }

        private Expression parseAdditive() {
            Expression expression = parseMultiplicative();
            while (true) {
                skipWhitespace();
                if (!take('+') && !take('-')) return expression;
                char operator = source.charAt(position - 1);
                expression = new BinaryExpression(operator, expression, parseMultiplicative());
            }
        }

        private Expression parseMultiplicative() {
            Expression expression = parseUnary();
            while (true) {
                skipWhitespace();
                char operator;
                if (take('*')) operator = '*';
                else if (take('/')) operator = '/';
                else if (take('%')) operator = '%';
                else return expression;
                expression = new BinaryExpression(operator, expression, parseUnary());
            }
        }

        private Expression parseUnary() {
            skipWhitespace();
            if (take('+') || take('-')) {
                char operator = source.charAt(position - 1);
                enterDepth();
                try {
                    return new UnaryExpression(operator, parseUnary());
                } finally {
                    depth--;
                }
            }
            return parsePrimary();
        }

        private Expression parsePrimary() {
            skipWhitespace();
            if (atEnd()) throw error("Expected an expression");
            if (take('(')) return parseParenthesized();
            if (isDigit(current())) return parseInteger();
            if (isIdentifierStart(current())) return parseIdentifier();
            throw error("Unexpected token '" + current() + "'");
        }

        private Expression parseParenthesized() {
            enterDepth();
            try {
                Expression expression = parseAdditive();
                skipWhitespace();
                expect(')');
                return expression;
            } finally {
                depth--;
            }
        }

        private Expression parseInteger() {
            int start = position;
            while (!atEnd() && isDigit(current())) position++;
            return new LiteralExpression(Rational.of(new BigInteger(source.substring(start, position))));
        }

        private Expression parseIdentifier() {
            int start = position++;
            while (!atEnd() && isIdentifierPart(current())) position++;
            String identifier = source.substring(start, position);
            skipWhitespace();
            if (!take('(')) {
                if ("ctm".equalsIgnoreCase(identifier)) return new VariableExpression();
                throw error("Unknown identifier '" + identifier + "'");
            }
            return parseFunction(identifier);
        }

        private Expression parseFunction(String identifier) {
            enterDepth();
            try {
                List<Expression> arguments = new ArrayList<>();
                skipWhitespace();
                if (!take(')')) {
                    while (true) {
                        arguments.add(parseAdditive());
                        skipWhitespace();
                        if (take(')')) break;
                        expect(',');
                    }
                }

                String name = identifier.toLowerCase(java.util.Locale.ROOT);
                int expectedArguments = switch (name) {
                    case "min", "max" -> 2;
                    case "clamp" -> 3;
                    default -> throw error("Unknown function '" + identifier + "'");
                };
                if (arguments.size() != expectedArguments) {
                    throw error(identifier + " requires " + expectedArguments + " arguments");
                }
                return new FunctionExpression(name, List.copyOf(arguments));
            } finally {
                depth--;
            }
        }

        private void enterDepth() {
            depth++;
            if (depth > MAX_DEPTH) throw error("Formula nesting exceeds " + MAX_DEPTH);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!take(expected)) throw error("Expected '" + expected + "'");
        }

        private boolean take(char expected) {
            if (!atEnd() && current() == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(current())) position++;
        }

        private boolean atEnd() {
            return position >= source.length();
        }

        private char current() {
            return source.charAt(position);
        }

        private FormulaException error(String message) {
            return new FormulaException(message + " at position " + position);
        }

        private static boolean isIdentifierStart(char value) {
            return Character.isLetter(value) || value == '_';
        }

        private static boolean isIdentifierPart(char value) {
            return Character.isLetterOrDigit(value) || value == '_';
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }
    }
}
