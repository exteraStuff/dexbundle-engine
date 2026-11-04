package io.github.exterastuff.dexbundle.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface PluginContext {
    /**
     * Получает собственный инстанс логгера.
     *
     * @return именованный логгер
     */
    @NotNull PluginLogger getLogger();

    /**
     * Выгружает текущий плагин и устанавливает статус ошибки, если она стала причиной этого.
     * <p>
     * Выгрузка происходит отложенно, поэтому вызов допустим и из {@link BasePlugin#onLoad()}, а
     * управление возвращается плагину сразу же.
     *
     * @param error ошибка приведшая к выгрузке плагина, может быть null
     */
    void unloadSelf(@Nullable Throwable error);

    /**
     * Регистрирует переданный сервис, который могут использовать другие плагины через метод
     * {@link #getService(Class)}.
     * <p>
     * Регистрация живёт ровно столько же, сколько сам плагин: при выгрузке плагина она снимается
     * движком.
     *
     * @param declaration    класс интерфейса сервиса
     * @param implementation инстанс класса реализующего интерфейс сервиса
     * @param <T>            интерфейс сервиса
     * @param <I>            тип класса реализующего интерфейс сервиса
     */
    <T, I extends T> void registerService(@NotNull Class<T> declaration, @NotNull I implementation);

    /**
     * Получает сервис, зарегистрированный другим плагином через
     * {@link #registerService(Class, Object)}.
     *
     * @param klass класс интерфейса сервиса
     * @param <T>   интерфейс сервиса
     *
     * @return инстанс класса реализующего интерфейс сервиса, если он был зарегистрирован, иначе
     * {@code null}
     */
    <T> @Nullable T getService(@NotNull Class<T> klass);
}
