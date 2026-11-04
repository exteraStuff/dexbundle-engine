package io.github.exterastuff.dexbundle.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook;

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

    /**
     * Устанавливает хук на метод или конструктор и привязывает его к текущему плагину.
     * <p>
     * При выгрузке плагина движок автоматически снимает установленные через этот метод хуки. Для
     * досрочного снятия хука используйте {@link #removeHook(XC_MethodHook.Unhook)}.
     *
     * @param targetMember метод или конструктор, на который устанавливается хук
     * @param methodHook   обработчик вызовов метода или конструктора
     *
     * @return объект для снятия установленного хука
     */
    @NotNull XC_MethodHook.Unhook installHook(@NotNull Member targetMember, @NotNull XC_MethodHook methodHook);

    /**
     * Снимает хук, зарегистрированный в текущем контексте через
     * {@link #installHook(Member, XC_MethodHook)}, и удаляет его из учёта движка.
     *
     * @param methodUnhook объект для снятия хука
     *
     * @return {@code true}, если хук был зарегистрирован в текущем контексте и снят, иначе
     * {@code false}
     */
    boolean removeHook(@NotNull XC_MethodHook.Unhook methodUnhook);
}
